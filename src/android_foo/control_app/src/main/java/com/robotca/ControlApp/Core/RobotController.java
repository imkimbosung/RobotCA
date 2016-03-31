package com.robotca.ControlApp.Core;

import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Log;

import com.robotca.ControlApp.ControlApp;
import com.robotca.ControlApp.Core.Plans.RobotPlan;
import com.robotca.ControlApp.R;

import org.ros.message.MessageListener;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.Node;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;
import org.ros.node.topic.Publisher;
import org.ros.node.topic.Subscriber;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import geometry_msgs.Point;
import geometry_msgs.Pose;
import geometry_msgs.Quaternion;
import geometry_msgs.Twist;
import nav_msgs.Odometry;
import sensor_msgs.LaserScan;
import sensor_msgs.NavSatFix;

/**
 * Manages receiving data from, and sending commands to, a connected Robot.
 *
 * Created by Michael Brunson on 2/13/16.
 */
public class RobotController implements NodeMain, Savable {

    // Logcat Tag
    private static final String TAG = "RobotController";

    // The parent Context
    private final ControlApp context;

    // Whether the RobotController has been initialized
    private boolean initialized;

    // Timer for periodically publishing velocity commands
    private Timer publisherTimer;
    // Indicates when a velocity command should be published
    private boolean publishVelocity;

    // Publisher for velocity commands
    private Publisher<Twist> movePublisher;
    // Contains the current velocity plan to be published
    private Twist currentVelocityCommand;

    // Subscriber to NavSatFix data
    private Subscriber<NavSatFix> navSatFixSubscriber;
    // The most recent NavSatFix
    private NavSatFix navSatFix;
    // Lock for synchronizing accessing and receiving the current NatSatFix
    private final Object navSatFixMutex = new Object();

    // Subscriber to LaserScan data
    private Subscriber<LaserScan> laserScanSubscriber;
    // The most recent LaserScan
    private LaserScan laserScan;
    // Lock for synchronizing accessing and receiving the current LaserScan
    private final Object laserScanMutex = new Object();

    // Subscriber to Odometry data
    private Subscriber<Odometry> odometrySubscriber;
    // The most recent Odometry
    private Odometry odometry;
    // Lock for synchronizing accessing and receiving the current Odometry
    private final Object odometryMutex = new Object();

    // Subscriber to Pose data
    private Subscriber<Pose> poseSubscriber;
    // The most recent Pose
    private Pose pose;
    // Lock for synchronizing accessing and receiving the current Pose
    private final Object poseMutex = new Object();

    // The currently running RobotPlan
    private RobotPlan motionPlan;
    // The currently paused RobotPlan
    private int pausedPlanId;

    // The node connected to the Robot on which data can be sent and received
    private ConnectedNode connectedNode;

    // Listener for LaserScans
    private final ArrayList<MessageListener<LaserScan>> laserScanListeners;
    // Listener for Odometry
    private ArrayList<MessageListener<Odometry>> odometryListeners;
    // Listener for NavSatFix
    private ArrayList<MessageListener<NavSatFix>> navSatListeners;

    /**
     * LocationProvider subscribers can register to to receive location updates.
     */
    public final LocationProvider LOCATION_PROVIDER;

    // The Robot's starting position
    private static Point startPos;
    // The Robot's last recorded position
    private static Point currentPos;
    // The Robot's last recorded orientation
    private static Quaternion rotation;
    // The Robot's last recorded speed
    private static double speed;
    // The Robot's last recorded turn rate
    private static double turnRate;

    // Bundle ID for pausedPlan
    private static final String PAUSED_PLAN_BUNDLE_ID = "com.robotca.ControlApp.Core.RobotController.pausedPlan";

    // Constant for no motion plan
    private static final int NO_PLAN = -1;

    /**
     * Creates a RobotController.
     * @param context The Context the RobotController belongs to.
     */
    public RobotController(ControlApp context) {
        this.context = context;

        this.initialized = false;

        this.laserScanListeners = new ArrayList<>();
        this.odometryListeners = new ArrayList<>();
        this.navSatListeners = new ArrayList<>();

        this.LOCATION_PROVIDER = new LocationProvider();
        this.addNavSatFixListener(this.LOCATION_PROVIDER);

        startPos = null;
        currentPos = null;
        rotation = null;
    }

    /**
     * Adds an Odometry listener.
     * @param l The listener
     */
    public void addOdometryListener(MessageListener<Odometry> l) {
        odometryListeners.add(l);
    }

    /**
     * Adds a NavSatFix listener.
     * @param l The listener
     */
    public void addNavSatFixListener(MessageListener<NavSatFix> l) {
        navSatListeners.add(l);
    }

    /**
     * Adds a LaserScan listener.
     * @param l The listener
     */
    public void addLaserScanListener(MessageListener<LaserScan> l) {
        synchronized (laserScanListeners) {
            laserScanListeners.add(l);
        }
    }

    /**
     * Removes a LaserScan listener.
     * @param l The listener
     */
    public void removeLaserScanListener(MessageListener<LaserScan> l) {

        synchronized (laserScanListeners) {
            laserScanListeners.remove(l);
        }
    }

    /**
     * Initializes the RobotController.
     * @param nodeMainExecutor The NodeMainExecutor on which to execute the NodeConfiguration.
     * @param nodeConfiguration The NodeConfiguration to execute
     */
    public void initialize(NodeMainExecutor nodeMainExecutor, NodeConfiguration nodeConfiguration) {
        nodeMainExecutor.execute(this, nodeConfiguration.setNodeName("android/robot_controller"));
    }

    /**
     * Runs the specified RobotPlan on the Robot.
     * @param plan The RobotPlan
     */
    public void runPlan(RobotPlan plan) {
        stop();
        pausedPlanId = NO_PLAN;

        publishVelocity = true;

        motionPlan = plan;
        motionPlan.run(this);
    }

    /**
     * Attempts to resume a stopped RobotPlan.
     * @return True if a RobotPlan was resumed
     */
    public boolean resumePlan() {
        if (pausedPlanId != NO_PLAN) {
            runPlan(ControlMode.getRobotPlan(context, ControlMode.values()[pausedPlanId]));
            return true;
        }

        return false;
    }

    /**
     * @return True if there is a paused RobotPlan, false otherwise
     */
    public boolean hasPausedPlan() {
        return pausedPlanId != NO_PLAN;
    }

    /**
     * @return The current RobotPlan
     */
    public RobotPlan getMotionPlan() {
        return motionPlan;
    }

    /**
     * Stops the Robot's current motion and any RobotPlan that may be running.
     * @return True if a resumable RobotPlan was stopped
     */
    public boolean stop() {

        pausedPlanId = NO_PLAN;

        if (motionPlan != null) {
            motionPlan.stop();

            if (motionPlan.isResumable()) {
                pausedPlanId = motionPlan.getControlMode() == null ? NO_PLAN: motionPlan.getControlMode().ordinal();
            }

            motionPlan = null;
        }

        publishVelocity = false;
        publishVelocity(0, 0, 0);

        if(movePublisher != null){
            movePublisher.publish(currentVelocityCommand);
        }

        return pausedPlanId != NO_PLAN;
    }

    /**
     * Sets the next values of the next velocity to publish.
     * @param linearVelocityX Linear velocity in the x direction
     * @param linearVelocityY Linear velocity in the y direction
     * @param angularVelocityZ Angular velocity about the z axis
     */
    public void publishVelocity(double linearVelocityX, double linearVelocityY,
                                double angularVelocityZ) {
        if (currentVelocityCommand != null) {
            currentVelocityCommand.getLinear().setX(linearVelocityX);
            currentVelocityCommand.getLinear().setY(-linearVelocityY);
            currentVelocityCommand.getLinear().setZ(0);
            currentVelocityCommand.getAngular().setX(0);
            currentVelocityCommand.getAngular().setY(0);
            currentVelocityCommand.getAngular().setZ(-angularVelocityZ);
        } else {
            Log.w("Emergency Stop", "currentVelocityCommand is null");
        }
    }

    /**
     * Same as above, but forces the velocity to be published.
     * @param linearVelocityX Linear velocity in the x direction
     * @param linearVelocityY Linear velocity in the y direction
     * @param angularVelocityZ Angular velocity about the z axis
     */
    public void forceVelocity(double linearVelocityX, double linearVelocityY,
                              double angularVelocityZ) {
        publishVelocity = true;
        publishVelocity(linearVelocityX, linearVelocityY, angularVelocityZ);
    }

    /**
     * @return The default node name for the RobotController
     */
    @Override
    public GraphName getDefaultNodeName() {
        return GraphName.of("android/robot_controller");
    }

    /**
     * Callback for when the RobotController is connected.
     * @param connectedNode The ConnectedNode the RobotController is connected through
     */
    @Override
    public void onStart(ConnectedNode connectedNode) {
        this.connectedNode = connectedNode;
        initialize();
    }

    /*
     * Initializes the RobotController.
     */
    public void initialize() {
        if (!initialized && connectedNode != null) {

            // Shutdown any topics that may be running
            shutdownTopics();

            // Get the correct topic names
            String moveTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_joystick_topic", context.getString(R.string.joy_topic));
            String navSatTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_navsat_topic", context.getString(R.string.navsat_topic));
            String laserScanTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_laser_scan_topic", context.getString(R.string.laser_scan_topic));
            String odometryTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_odometry_topic", context.getString(R.string.odometry_topic));
            String poseTopic = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("edittext_pose_topic", context.getString(R.string.pose_topic));

            // Start the move publisher
            movePublisher = connectedNode.newPublisher(moveTopic, Twist._TYPE);
            currentVelocityCommand = movePublisher.newMessage();

            publisherTimer = new Timer();
            publisherTimer.schedule(new TimerTask() {
                @Override
                public void run() { if (publishVelocity) {
                        movePublisher.publish(currentVelocityCommand);
                    }
                }
            }, 0, 80);
            publishVelocity = false;

            // Start the NavSatFix subscriber
            navSatFixSubscriber = connectedNode.newSubscriber(navSatTopic, NavSatFix._TYPE);
            navSatFixSubscriber.addMessageListener(new MessageListener<NavSatFix>() {
                @Override
                public void onNewMessage(NavSatFix navSatFix) {
                    setNavSatFix(navSatFix);
                }
            });

            // Start the LaserScan subscriber
            laserScanSubscriber = connectedNode.newSubscriber(laserScanTopic, LaserScan._TYPE);
            laserScanSubscriber.addMessageListener(new MessageListener<LaserScan>() {
                @Override
                public void onNewMessage(LaserScan laserScan) {
                    setLaserScan(laserScan);
                }
            });

            // Start the Odometry subscriber
            odometrySubscriber = connectedNode.newSubscriber(odometryTopic, Odometry._TYPE);
            odometrySubscriber.addMessageListener(new MessageListener<Odometry>() {
                @Override
                public void onNewMessage(Odometry odometry) {
                    setOdometry(odometry);
                }
            });

            // Start the Pose subscriber
            poseSubscriber = connectedNode.newSubscriber(poseTopic, Pose._TYPE);
            poseSubscriber.addMessageListener(new MessageListener<Pose>() {
                @Override
                public void onNewMessage(Pose pose) {
                    setPose(pose);
                }
            });

            initialized = true;
        }
    }

    /**
     * Shuts down all topics.
     */
    public void shutdownTopics() {
        if(publisherTimer != null) {
            publisherTimer.cancel();
        }

        if (movePublisher != null) {
            movePublisher.shutdown();
        }

        if (navSatFixSubscriber != null) {
            navSatFixSubscriber.shutdown();
        }

        if (laserScanSubscriber != null) {
            laserScanSubscriber.shutdown();
        }

        if(odometrySubscriber != null){
            odometrySubscriber.shutdown();
        }

        if(poseSubscriber != null){
            poseSubscriber.shutdown();
        }
    }

    /**
     * Callback for when the RobotController is shutdown.
     * @param node The Node
     */
    @Override
    public void onShutdown(Node node) {
        shutdownTopics();
    }

    /**
     * Callback for when the shutdown is complete.
     * @param node The Node
     */
    @Override
    public void onShutdownComplete(Node node) {
        this.connectedNode = null;
    }

    /**
     * Callback indicating an error has occurred.
     * @param node The Node
     * @param throwable The error
     */
    @Override
    public void onError(Node node, Throwable throwable) {
        Log.e(TAG, "", throwable);
    }

    /**
     * @return The most recently received LaserScan
     */
    public LaserScan getLaserScan() {
        synchronized (laserScanMutex) {
            return laserScan;
        }
    }

    /**
     * Sets the current LaserScan.
     * @param laserScan The LaserScan
     */
    protected void setLaserScan(LaserScan laserScan) {
        synchronized (laserScanMutex) {
            this.laserScan = laserScan;
        }

        // Call the listener callbacks
        synchronized (laserScanListeners) {
            for (MessageListener<LaserScan> listener : laserScanListeners) {
                listener.onNewMessage(laserScan);
            }
        }
    }

    /**
     * @return The most recently received NavSatFix
     */
    public NavSatFix getNavSatFix() {
        synchronized (navSatFixMutex) {
            return navSatFix;
        }
    }

    /**
     * Sets the current NavSatFix.
     * @param navSatFix The NavSatFix
     */
    protected void setNavSatFix(NavSatFix navSatFix) {
        synchronized (navSatFixMutex) {
            this.navSatFix = navSatFix;

            // Call the listener callbacks
            for (MessageListener<NavSatFix> listener: navSatListeners) {
                listener.onNewMessage(navSatFix);
            }
        }
    }

    /**
     * @return The most recently received Odometry.
     */
    public Odometry getOdometry() {
        synchronized (odometryMutex) {
            return odometry;
        }
    }

    /**
     * Sets the current Odometry.
     * @param odometry The Odometry
     */
    protected void setOdometry(Odometry odometry) {
        synchronized (odometryMutex) {
            this.odometry = odometry;

            // Call the listener callbacks
            for (MessageListener<Odometry> listener: odometryListeners) {
                listener.onNewMessage(odometry);
            }

            // Record position TODO this should be moved to setPose() but that's not being called for some reason
            if (startPos == null) {
                startPos = odometry.getPose().getPose().getPosition();
            } else {
                currentPos = odometry.getPose().getPose().getPosition();
            }
            rotation = odometry.getPose().getPose().getOrientation();

            // Record speed and turnrate
            speed = odometry.getTwist().getTwist().getLinear().getX();
            turnRate = odometry.getTwist().getTwist().getAngular().getZ();
        }
    }

    /**
     * @return The most recently received Pose.
     */
    public Pose getPose() {
        synchronized (poseMutex) {
            return pose;
        }
    }

    /**
     * Sets the current Pose.
     * @param pose The Pose
     */
    public void setPose(Pose pose){
        synchronized (poseMutex){
            this.pose = pose;
        }

        Log.d("RobotController", "Pose Set");
//        // Record position
//        if (startPos == null) {
//            startPos = pose.getPosition();
//        } else {
//            currentPos = pose.getPosition();
//        }
//        rotation = pose.getOrientation();
    }

    /**
     * Load from a Bundle.
     *
     * @param bundle The Bundle
     */
    @Override
    public void load(@NonNull Bundle bundle) {
        pausedPlanId = bundle.getInt(PAUSED_PLAN_BUNDLE_ID, NO_PLAN);
    }

    /**
     * Save to a Bundle.
     *
     * @param bundle The Bundle
     */
    @Override
    public void save(@NonNull Bundle bundle) {
        bundle.putInt(PAUSED_PLAN_BUNDLE_ID, pausedPlanId);
    }

    /**
     * @return The Robot's last reported x position
     */
    public static double getX() {
        if (currentPos == null)
            return 0.0;
        else
            return currentPos.getX() - startPos.getX();
    }

    /**
     * @return The Robot's last reported y position
     */
    public static double getY() {
        if (currentPos == null)
            return 0.0;
        else
            return currentPos.getY() - startPos.getY();
    }

    /**
     * @return The Robot's last reported heading in radians
     */
    public static double getHeading() {
        if (rotation == null)
            return 0.0;
        else
            return Utils.getHeading(org.ros.rosjava_geometry.Quaternion.fromQuaternionMessage(rotation));
    }

    /**
     * @return The Robot's last reported speed in the range [-1, 1].
     */
    public static double getSpeed() {
        return speed;
    }

    /**
     * @return The Robot's last reported turn rate in the range[-1, 1].
     */
    public static double getTurnRate() {
        return turnRate;
    }
}
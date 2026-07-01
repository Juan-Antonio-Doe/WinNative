package com.winlator.cmod.runtime.system;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.winlator.cmod.R;
import com.winlator.cmod.app.shell.UnifiedActivity;
import com.winlator.cmod.runtime.display.XServerDisplayActivity;
import com.winlator.cmod.runtime.display.environment.XEnvironment;
import com.winlator.cmod.runtime.display.xserver.XServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.winlator.cmod.shared.android.NotificationHelper;

import timber.log.Timber;

/**
 * Foreground service that keeps the WinNative process alive while a wine
 * session is in the background or while a component download/install is
 * running. Without it, Android can reap the app process when the screen is
 * locked, taking the wine container (and any in-flight download) with it.
 *
 * Reasons are reference-counted via static helpers. The service stops itself
 * once no reasons remain. On task removal (user swipe-away) it does a
 * defensive wine cleanup and lets the process exit, matching the previous
 * "swipe = close" behaviour.
 */
public class SessionKeepAliveService extends Service {

    private static volatile SessionKeepAliveService instance;
    private static final String TAG = "SessionKeepAlive";
    private static final String EXTRA_TAG = "tag";
    private static final String ACTION_ENSURE_FOREGROUND =
            "com.winlator.cmod.action.ENSURE_FOREGROUND";

    private static final String CHANNEL_ID = "winnative_session_keepalive";

    private static final String ACTION_SESSION_START = "com.winlator.cmod.action.SESSION_START";
    private static final String ACTION_SESSION_STOP = "com.winlator.cmod.action.SESSION_STOP";
    private static final String ACTION_SESSION_PAUSE = "com.winlator.cmod.action.SESSION_PAUSE";
    private static final String ACTION_SESSION_RESUME = "com.winlator.cmod.action.SESSION_RESUME";
    private static final String ACTION_DL_START = "com.winlator.cmod.action.SESSION_DL_START";
    private static final String ACTION_DL_STOP = "com.winlator.cmod.action.SESSION_DL_STOP";
    private static final String ACTION_UPDATE_COMPONENT = "com.winlator.cmod.action.UPDATE_COMPONENT";
    private static final String ACTION_REMOVE_COMPONENT = "com.winlator.cmod.action.REMOVE_COMPONENT";

    public static final String COMPONENT_STEAM = "Steam";
    public static final String COMPONENT_EPIC = "Epic";
    public static final String COMPONENT_GOG = "GOG";
    public static final String COMPONENT_CONTAINER = "Container";

    private static final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private static final HashSet<String> activeDownloads = new HashSet<>();
    private static final AtomicBoolean serviceRunning = new AtomicBoolean(false);

    private static volatile XEnvironment activeEnvironment;
    private static volatile XServer activeXServer;

    private static volatile boolean isContainerPaused = false;

    //    private PowerManager.WakeLock wakeLock;
    //    private WifiManager.WifiLock wifiLock;
    private NotificationHelper notificationHelper;
    private int notificationId = -1;
    private static final String NOTIFICATION_ID_NAME = "winnative.keepAlive";
    private static boolean isActivityVisible = false;

    // Tracks active components and their notification messages
    private static final Map<String, String> activeComponents = new ConcurrentHashMap<>();
    // Component names constants


    private final Handler protectionHandler = new Handler(Looper.getMainLooper());
    private final Runnable protectionRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive.get() || !isContainerPaused) return;
            Timber.tag(TAG).d("Running periodic HEARTBEAT protection for a container session");
            new Thread(() -> {
                try {
//                        ProcessHelper.protectAllWineProcesses();
                    LogManager.log(TAG, "Heartbeat: Keeping container alive...", getApplicationContext());
                } catch (Exception e) {
                    LogManager.logE(TAG, "Periodic HEARTBEAT protection sweep failed", e, getApplicationContext());
                }
            }, "WineOomProtection").start();
            protectionHandler.postDelayed(this, 2 * 60 * 1000L); // Every 2 minutes
        }
    };

    // ===================================================================
    // Container / game session lifecycle
    // ===================================================================

    public static void startSession(Context ctx) {
        if (ctx == null) return;
        sessionActive.set(true);
        isContainerPaused = false;
        isActivityVisible = true;
        LogManager.log(TAG, "startSession", ctx);
        updateForegroundState(ctx);
//        sendCommand(ctx, ACTION_SESSION_START, null);
    }

    public static void onPauseSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.get()) {
            LogManager.logW(TAG, "onPauseSession called with no active session; ignoring", null, ctx);
            return;
        }
        isContainerPaused = true;
        isActivityVisible = false;
        LogManager.log(TAG, "onPauseSession", ctx);
//        startProtectionHeartbeat();
        updateForegroundState(ctx);
//        sendCommand(ctx, ACTION_SESSION_PAUSE, null);
    }

    public static void onResumeSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.get()) {
            LogManager.logW(TAG, "onResumeSession called with no active session; ignoring", null, ctx);
            return;
        }
        isContainerPaused = false;
        isActivityVisible = true;
        LogManager.log(TAG, "onResumeSession", ctx);
        // stopProtectionHeartbeat();
        updateForegroundState(ctx);
//        sendCommand(ctx, ACTION_SESSION_RESUME, null);
    }

    public static void stopSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.compareAndSet(true, false)) return;
        isContainerPaused = false;
//        LogManager.log(ctx, TAG, "stopSession");
        // stopProtectionHeartbeat();
        teardownEnvironmentAsync();
        updateForegroundState(ctx);
        LogManager.log(TAG, "Stopping game session in keep-alive service. Request by: " + Objects.requireNonNull(ctx.getClass().getName()), ctx);
//        sendCommand(ctx, ACTION_SESSION_STOP, null);
    }

    public static boolean isSessionActive() {
        return sessionActive.get();
    }

    // Possibly, this method is useless because it does not restart
    // in the background unless another class calls this class.
    private static void startProtectionHeartbeat() {
        SessionKeepAliveService svc = instance;
        if (svc == null) return;
        svc.protectionHandler.removeCallbacks(svc.protectionRunnable);
        svc.protectionHandler.post(svc.protectionRunnable);
    }

    private static void stopProtectionHeartbeat() {
        SessionKeepAliveService svc = instance;
        if (svc != null) svc.protectionHandler.removeCallbacks(svc.protectionRunnable);
    }

    // Capture-then-null before handing off, so a second stopSession() call,
    // or a racing reader, can never observe a half-torn-down environment.
    private static void teardownEnvironmentAsync() {
        final XEnvironment env = activeEnvironment;
        activeEnvironment = null;
        activeXServer = null;
        if (env == null) return;
        new Thread(() -> {
            try {
                env.stopEnvironmentComponents();
            } catch (Exception e) {
//                Timber.tag(TAG).e(e, "Failed to stop environment components during session stop");
                LogManager.logE(TAG, "Failed to stop environment components during session stop", e, instance.getApplicationContext());
            }
        }, "XServerTeardown").start();
    }

    public static XEnvironment getActiveEnvironment() {
        return activeEnvironment;
    }

    public static void setActiveEnvironment(XEnvironment environment) {
        activeEnvironment = environment;
    }

    public static XServer getActiveXServer() {
        return activeXServer;
    }

    public static void setActiveXServer(XServer xServer) {
        activeXServer = xServer;
    }

    // ===================================================================
    // Store component tracking (Steam/Epic/GOG "I'm doing background work")
    // ===================================================================

    public static void startComponent(Context ctx, String componentName, String message) {
        if (ctx == null || componentName == null) return;
        activeComponents.put(componentName, message != null ? message : "");
        LogManager.log(TAG, "startComponent: " + componentName, ctx);
        updateForegroundState(ctx);
    }

    /*public static void startComponent(Context context, String componentName, String message) {
        activeComponents.put(componentName, message != null ? message : "");

        Intent intent = new Intent(context, SessionKeepAliveService.class);
        intent.setAction(ACTION_UPDATE_COMPONENT);
        intent.putExtra("component_name", componentName);
        intent.putExtra("component_message", message);

        try {
            context.startService(intent);
        } catch (Exception e) {
            // SILENT CATCH: This prevents the app from crashing.
            // If it fails, it means the app is in background.
            // The service will start correctly next time the app goes to foreground.
            String logMsg = "BackgroundServiceStartNotAllowed: Could not start master service for " + componentName;
            Log.w(TAG, logMsg);
            LogManager.logWarn(context, TAG, logMsg, e);
        }
    }*/

    public static void stopComponent(Context ctx, String componentName) {
        if (ctx == null || componentName == null) return;
        if (activeComponents.remove(componentName) == null) return;
        LogManager.log(TAG, "stopComponent: " + componentName, ctx);
        updateForegroundState(ctx);
    }

    /*public static void stopComponent(Context context, String componentName) {
        // 1. Update data
        activeComponents.remove(componentName);

        // 2. ONLY send the intent if the service is ALREADY running.
        // This avoids starting the service just to stop a component.
        if (serviceRunning.get()) {
            Intent intent = new Intent(context, SessionKeepAliveService.class);
            intent.setAction(ACTION_REMOVE_COMPONENT);
            intent.putExtra("component_name", componentName);
            try {
                context.startService(intent);
            } catch (Exception e) {
                Log.w(TAG, "Failed to send stop component to master service (App in background)");
            }
        }
    }*/

    // ===================================================================
    // Background download tracking
    // ===================================================================

    public static void startDownload(Context ctx, String tag) {
        if (ctx == null) return;
        String key = tag == null ? "default" : tag;
        boolean added;
        synchronized (activeDownloads) { added = activeDownloads.add(key); }
        if (added) {
            Timber.tag(TAG).d("startDownload: %s", key);
            updateForegroundState(ctx);
//            sendCommand(ctx, ACTION_DL_START, key);
        }
    }

    public static void stopDownload(Context ctx, String tag) {
        if (ctx == null) return;
        String key = tag == null ? "default" : tag;
        boolean removed;
        synchronized (activeDownloads) { removed = activeDownloads.remove(key); }
        if (removed) {
            Timber.tag(TAG).d("stopDownload: %s", key);
            updateForegroundState(ctx);
//            sendCommand(ctx, ACTION_DL_STOP, key);
        }
    }

    // ===================================================================
    // Foreground validation logic
    // ===================================================================

    private static boolean hasReason() {
        return sessionActive.get() || !activeDownloads.isEmpty() || !activeComponents.isEmpty();
        /*if (sessionActive.get()) return true;
        synchronized (activeDownloads) {
            return !activeDownloads.isEmpty();
        }*/
    }

    // Single chokepoint for every caller (session, components, downloads).
    // Mutates state first, then either talks to the already-alive instance
    // directly (no Intent, no restriction — it's just a method call) or, only
    // if the service doesn't exist yet, asks the OS to create it.
    private static synchronized void updateForegroundState(Context ctx) {
        SessionKeepAliveService svc = instance;

        if (hasReason()) {
            if (svc != null) {
                svc.ensureForeground();
            } else {
                Context app = ctx.getApplicationContext();
                Intent intent = new Intent(app, SessionKeepAliveService.class);
                intent.setAction(ACTION_ENSURE_FOREGROUND);
                try {
                    androidx.core.content.ContextCompat.startForegroundService(app, intent);
                } catch (Exception e) {
                    LogManager.logW(TAG, "Failed to start keep-alive service", e, ctx);
                }
            }
        } else if (svc != null) {
            LogManager.log(TAG, "No active reason remains; stopping keep-alive service", ctx);
            svc.stopForegroundCompat();
            svc.stopSelf();
        }
    }

    // ===================================================================
    // Foreground class logic
    // ===================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Initialize the helper using the application context
        notificationHelper = new NotificationHelper(getApplicationContext());
        notificationHelper.createNotificationChannel(); // Replace ensureChannel() method.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // State is already current by the time this runs — every caller mutates
        // it before this Intent is ever sent. This just reconciles the actual
        // foreground/running status against that state.
        if (hasReason()) {
            ensureForeground();
            serviceRunning.set(true);
        } else {
                Timber.tag(TAG).d("onStartCommand found no active reason; stopping immediately");
            stopForegroundCompat();
            stopSelf();
            serviceRunning.set(false);
        }
        return START_NOT_STICKY;
    }

    private void ensureForeground() {
        boolean containerActive = sessionActive.get();
        // Only show Exit button if container is running AND app is in background
        boolean showExit = containerActive && !isActivityVisible;

        // Determine target activity: Game screen if active, else Main menu
        Class<?> targetActivity = containerActive ? XServerDisplayActivity.class : UnifiedActivity.class;

        Notification n = notificationHelper.createForegroundNotification(
                getNotificationContent(),
                "WinNative", // Title
                SessionKeepAliveService.class, // Service class for the 'Exit' action
                showExit ? ACTION_SESSION_STOP : null, // Exit only for backgrounded container
                targetActivity // Activity class for the 'Open' (notification tap) action
        );

        // Cache the ID: repeated startForeground() calls with the same ID update
        // the existing notification instead of risking a fresh one each time
        // pause/resume/component state changes.
        if (notificationId == -1) {
            notificationId = notificationHelper.generateNotificationId(this, NOTIFICATION_ID_NAME);
        }

        try {
            // Only call startForeground the first time. Use notify() for updates.
            if (!serviceRunning.get()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                }
                else {
                    startForeground(notificationId, n);
                }
            }
            else {
                // Standard notification update
                notificationHelper.notify(notificationId, n);
            }
        } catch (Exception e) {
            LogManager.logW(TAG, "Failed to startForeground", e, this);
        }
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            LogManager.logW(TAG, "Failed to stopForeground", e, this);
        }
    }

    @Override
    public void onTimeout(int startId, int fstype) {
        super.onTimeout(startId, fstype);
        Timber.tag(TAG).w("Service reached 6-hour limit for dataSync. Stopping gracefully.");

        // Stop the service and cleanup
        sessionActive.set(false);
        isContainerPaused = false;
        // stopProtectionHeartbeat();
        stopForegroundCompat();
        stopSelf();
    }

    private static void sendCommand(Context ctx, String action, @Nullable String tag) {
        Context app = ctx.getApplicationContext();
        Intent intent = new Intent(app, SessionKeepAliveService.class);
        intent.setAction(action);
        if (tag != null) intent.putExtra(EXTRA_TAG, tag);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    (ACTION_SESSION_START.equals(action) || ACTION_DL_START.equals(action))) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Exception e) {
            // If starting the service fails, try starting it as a foreground service as a fallback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            }
            Log.w(TAG, "Failed to send command " + action, e);
        }
    }

/*    @Override
    public void onCreate() {
        LogManager.logLastExitReasons(getApplicationContext());

        super.onCreate();
//        generateNotificationId();
        instance = this;

        // Initialize the helper using the application context
        notificationHelper = new NotificationHelper(getApplicationContext());
        notificationHelper.createNotificationChannel(); // Replace ensureChannel() method.

        // Keep the CPU alive to prevent OS from killing the process when the screen is off.
        *//*PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WinNative:KeepAlive");
        }*//*

        // Keep the Wi-Fi alive to prevent network interruptions. Useful for games that stream assets from the network or have online features.
        *//*WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
        if (wm != null) {
            int lockType = WifiManager.WIFI_MODE_FULL;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lockType = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            }
            wifiLock = wm.createWifiLock(lockType, "WinNative:WifiKeepAlive");
        }*//*

//        ensureChannel();
    }*/

    /*@Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_SESSION_START.equals(action)) {
            sessionActive.set(true);
            isContainerPaused = false;
        } else if (ACTION_SESSION_PAUSE.equals(action)) {
            isContainerPaused = true;
            protectionHandler.removeCallbacks(protectionRunnable);
            protectionHandler.post(protectionRunnable);
        } else if (ACTION_SESSION_RESUME.equals(action)) {
            isContainerPaused = false;
            protectionHandler.removeCallbacks(protectionRunnable);
        } else if (ACTION_UPDATE_COMPONENT.equals(action)) {
            String name = intent.getStringExtra("component_name");
            String msg = intent.getStringExtra("component_message");
            if (name != null) activeComponents.put(name, msg);
        } else if (ACTION_REMOVE_COMPONENT.equals(action)) {
            String name = intent.getStringExtra("component_name");
            if (name != null) activeComponents.remove(name);
        } else if (ACTION_SESSION_STOP.equals(action)) {
            sessionActive.set(false);
            isContainerPaused = false;
            protectionHandler.removeCallbacks(protectionRunnable);
            if (activeEnvironment != null) {
                final XEnvironment env = activeEnvironment;
                activeEnvironment = null;
                activeXServer = null;
                new Thread(() -> {
                    try {
                        env.stopEnvironmentComponents();
                    } catch (Exception e) {
                        LogManager.logError(this, TAG, "Failed to stop environment components during session stop", e);
                    }
                }, "XServerTeardown").start();
            }
        }

        // Ensure wake lock, wifi lock and OOM adj are correct based on current state
        *//*if (hasReason()) {
//            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
//            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
//            ProcessHelper.setOomScoreAdj(android.os.Process.myPid(), -1000);
            *//**//*new Thread(() -> {
                try {
                    ProcessHelper.protectAllWineProcesses();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to run initial OOM protection", e);
                }
            }, "InitialWineOomProtection").start();*//**//*
        } else {
//            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
//            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
//            ProcessHelper.setOomScoreAdj(android.os.Process.myPid(), 0);
        }*//*

        if (hasReason()) {
            // Always promote to foreground first so Android does not consider
            // the start a violation (and so the notification reflects current
            // reasons), even if the command immediately tells us to stop.
            ensureForeground();
            serviceRunning.set(true);
//            Log.d(TAG, "Service keep alive is running...");
        }
        else {
            LogManager.log(this, TAG, "No active reason; stopping keep-alive service");
            stopForegroundCompat();
            stopSelf();
            serviceRunning.set(false);
        }
        return START_NOT_STICKY;
    }*/

    /*private void ensureForeground() {
//        Notification n = buildNotification();

        boolean containerActive = sessionActive.get();
        // Only show Exit button if container is running AND app is in background
        boolean showExit = containerActive && !isActivityVisible;

        // Determine target activity: Game screen if active, else Main menu
        Class<?> targetActivity = containerActive ? XServerDisplayActivity.class : UnifiedActivity.class;

        Notification n = notificationHelper.createForegroundNotification(
                getNotificationContent(),
                "WinNative", // Title
                SessionKeepAliveService.class, // Service class for the 'Exit' action
                showExit ? ACTION_SESSION_STOP : null, // Exit only for backgrounded container
                targetActivity // Activity class for the 'Open' (notification tap) action
        );
        notificationId = notificationHelper.generateNotificationId(this, NOTIFICATION_ID_NAME);

        try {
            // Only call startForeground the first time. Use notify() for updates.
            if (!serviceRunning.get()) {
                *//*if (Build.VERSION.SDK_INT >= 34) {
                startForeground(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                }
                else *//*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                }
                else {
                    startForeground(notificationId, n);
                }
            }
            else {
                // Standard notification update
                notificationHelper.notify(notificationId, n);
            }

        } catch (Exception e) {
            LogManager.logWarn(this, TAG, "Failed to startForeground", e);
        }
    }*/

    public void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "WinNative session keep-alive",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                "Keeps WinNative running in the background so a paused game session or "
                        + "an active component download is not interrupted by screen lock.");
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
    }

    public Notification buildNotification() {
        String content = getNotificationContent();

        Intent openIntent = new Intent(this, XServerDisplayActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("WinNative")
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent)
                .build();
    }

    // ===================================================================
    // Cleaning methods
    // ===================================================================

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        LogManager.logI(TAG, "Task removed (user swipe). Tearing down session and exiting process.", this);

        sessionActive.set(false);
        isContainerPaused = false;
        isActivityVisible = false;
        synchronized (activeDownloads) { activeDownloads.clear(); }
        activeComponents.clear();
        // stopProtectionHeartbeat();

        // Give the activity's own onDestroy → performForcedSessionCleanup a
        // chance to run first; then defensively clean any wine processes that
        // might still be alive, and exit the process so swipe behaves like the
        // pre-existing "swipe-away closes everything" flow.
        new Thread(() -> {
            try {
                Thread.sleep(1500L);
                if (com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.isBionicHandoffActive()) {
                    try {
                        boolean kicked = com.winlator.cmod.feature.stores.steam.service.SteamService
                                .Companion.bionicHandoffReleaseAndKickPlayingSessionBlocking(true, 2500L);
                        LogManager.logI(TAG, "Task removal Steam cleanup: kickedPlayingSession=" + kicked, this);
                    } catch (Throwable t) {
                        LogManager.logW(TAG, "Task removal Steam cleanup failed", t, this);
                    }
                }
                ProcessHelper.terminateSessionProcessesAndWait(1500, true);
                ProcessHelper.drainDeadChildren("session keep-alive task removed");
            } catch (Throwable t) {
                LogManager.logW(TAG, "Defensive wine cleanup on task removal failed", t, this);
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                stopForegroundCompat();
                stopSelf();
                serviceRunning.set(false);
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> android.os.Process.killProcess(android.os.Process.myPid()), 500L);
            });
        }, "SessionKeepAliveCleanup").start();
    }

    @Override
    public void onDestroy() {
       // stopProtectionHeartbeat();
//        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
//        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        serviceRunning.set(false);
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /*public int generateNotificationId() {
        // Generate a unique ID based on the package name to avoid conflicts with other forks/flavors.
        String contextKey = getPackageName() + ".winnative.keepAlive";
        return notificationId = contextKey.hashCode() & 0x7FFFFFFF; // Avoid negative IDs
    }*/

    // ===================================================================
    // Utility methods
    // ===================================================================

    @NonNull
    private static String getNotificationContent() {
        // 1. HIGHEST PRIORITY: The game/container
        if (sessionActive.get()) {
            return isContainerPaused ? "Container session is paused" : "There is a container session running";
        }

        // 2. MEDIUM PRIORITY: Downloads
        synchronized (activeDownloads) {
            if (!activeDownloads.isEmpty()) return "Downloading and installing components in the background";
        }

        // 3. LOW PRIORITY: Active Stores
        if (!activeComponents.isEmpty()) {
            List<String> names = new ArrayList<>(activeComponents.keySet());
            return names.size() == 1
                    ? names.get(0) + " service is active"
                    : String.join(" and ", names) + " services are active";
        }
        return "WinNative is running in the background";
    }

    public static void stopAll(Context ctx) {
        stopSession(ctx);
        activeComponents.clear();

        // Stop Steam specifically
        com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.stop();

        // Finally stop the master service
        SessionKeepAliveService svc = instance;
        if (svc != null) {
            svc.stopForegroundCompat();
            svc.stopSelf();
        }
    }
}

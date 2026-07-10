package com.winlator.cmod.runtime.system;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.app.shell.UnifiedActivity;
import com.winlator.cmod.feature.stores.steam.utils.PrefManager;
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
//    public static final String COMPONENT_STEAM_FRIENDS = "Steam Friends";
    public static final String COMPONENT_EPIC = "Epic";
    public static final String COMPONENT_GOG = "GOG";

    private static final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private static final HashSet<String> activeDownloads = new HashSet<>();
    private static final AtomicBoolean serviceRunning = new AtomicBoolean(false);

    private static volatile XEnvironment activeEnvironment;
    private static volatile XServer activeXServer;

    private static volatile boolean isContainerPaused = false;

    // ── App visibility state ──────────────────────────────────────────────
    // isAppInBackground: true when no Activity is STARTED (ProcessLifecycleOwner).
    // isScreenLocked: true after ACTION_SCREEN_OFF, cleared by ACTION_USER_PRESENT.
    // Both are updated from the main thread; volatile is sufficient for reads.
    private static volatile boolean isAppInBackground = false;
    private static volatile boolean isScreenLocked = false;
    private BroadcastReceiver screenStateReceiver;

    private PowerManager.WakeLock wakeLock;
    //    private WifiManager.WifiLock wifiLock;

    private static volatile SharedPreferences prefs;
    private static final String PREF_USE_WAKELOCK = "enable_background_wakelock";
    private static final String PREF_HEARTBEAT_FREQUENCY = "background_heartbeat_frequency";
    private static long heartbeat_interval_ms = 2 * 60 * 1000L; // 2 minutes

    // Dedicated thread replaces Handler.postDelayed() — not subject to
    // main-looper message-queue deferral in low-power states.
    private volatile Thread heartbeatThread;
    private volatile boolean heartbeatRunning = false;

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
            }, "SessionOomProtection").start();
            protectionHandler.postDelayed(this, 2 * 60 * 1000L); // Every 2 minutes
        }
    };

    // ===================================================================
    // Container / game session lifecycle
    // ===================================================================

    public static void startSession(Context ctx) {
        if (ctx == null) return;
        prefs = PreferenceManager.getDefaultSharedPreferences(ctx.getApplicationContext());
        if (prefs != null) {
            int frequency = prefs.getInt(PREF_HEARTBEAT_FREQUENCY, 120);
            if (frequency > 0) {
                if (frequency < 5)
                    heartbeat_interval_ms = 5 * 1000L;
                else
                    heartbeat_interval_ms = frequency * 1000L;
            }
        }

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
        if (instance != null) {
            instance.acquireWakeLock();
            instance.runOomSweep();
            instance.startHeartbeat();
        }
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
        if (instance != null) {
            instance.stopHeartbeat();
            instance.releaseWakeLock();
        }
        updateForegroundState(ctx);
//        sendCommand(ctx, ACTION_SESSION_RESUME, null);
    }

    public static void stopSession(Context ctx) {
        if (ctx == null) return;
        if (!sessionActive.compareAndSet(true, false)) return;
        isContainerPaused = false;
//        LogManager.log(ctx, TAG, "stopSession");
        // stopProtectionHeartbeat();
        if (instance != null) {
            instance.stopHeartbeat();
            instance.releaseWakeLock();
        }
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

    public static boolean isAppInBackground()  { return isAppInBackground;  }
    public static boolean isDeviceLocked()     { return isScreenLocked;      }

    public static boolean isAppVisible() {
        return isAppInBackground || isScreenLocked;
    }

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
        return sessionActive.get() || !activeDownloads.isEmpty() || !activeComponents.isEmpty() ||
                (isAppVisible() && PrefManager.INSTANCE.getChatStayRunningOnExit());

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

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WinNative:SessionKeepAlive"
            );
            // setReferenceCounted(false): acquire/release are idempotent —
            // calling release() without a matching acquire() won't throw.
            wakeLock.setReferenceCounted(false);
        }

        // Seed initial state from current lifecycle rather than assuming foreground.
        isAppInBackground = !androidx.lifecycle.ProcessLifecycleOwner.get()
                .getLifecycle().getCurrentState()
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);

        androidx.lifecycle.ProcessLifecycleOwner.get()
                .getLifecycle()
                .addObserver(appLifecycleObserver);

        // Screen-lock detection. ACTION_SCREEN_OFF/USER_PRESENT are protected
        // broadcasts — dynamic registration only, no manifest entry needed.
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    isScreenLocked = true;
                    LogManager.log(TAG, "Screen turned off / device locked");
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    isScreenLocked = false;
                    LogManager.log(TAG, "Device unlocked (user present)");
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    // Screen on but keyguard may still be showing.
                    KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                    isScreenLocked = km != null && km.isKeyguardLocked();
                }
                updateForegroundState(context);
            }
        };

        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenStateReceiver, screenFilter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        // Handle the Exit button from the notification
        if (ACTION_SESSION_STOP.equals(action)) {
            boolean chatStayAlive = PrefManager.INSTANCE.getChatStayRunningOnExit();

            // If a game is running, and we want to keep chat alive, only stop the session.
            // updateForegroundState() will be called inside stopSession,
            // and it will update the notification to the "Chat" state.
            if (sessionActive.get() && chatStayAlive) {
                stopSession(this);
                cleanUpSession(this, "Exit button pressed");
            } else {
                // Otherwise, perform a full app shutdown.
                closeApp(this);
            }
            return START_NOT_STICKY;
        }

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
        // Only show Exit button if app is in background AND container is running or user wants to keep steam chat alive.
        boolean showExit = isAppVisible() && (containerActive || PrefManager.INSTANCE.getChatStayRunningOnExit());

        // Determine target activity: Game screen if active, else Main menu
        Class<?> targetActivity = containerActive ? XServerDisplayActivity.class : UnifiedActivity.class;

        Notification n = notificationHelper.createForegroundNotification(
                getNotificationContent(),
                "WinNative",
                SessionKeepAliveService.class,
                showExit ? ACTION_SESSION_STOP : null, // Exit only for backgrounded app
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
            if (ACTION_SESSION_START.equals(action) || ACTION_DL_START.equals(action)) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Exception e) {
            // If starting the service fails, try starting it as a foreground service as a fallback.
            app.startForegroundService(intent);
            Timber.tag(TAG).w(e, "Failed to send command %s", action);
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

        resetLocalState();
        // stopProtectionHeartbeat();

        performDefensiveCleanupAndExit(this);
    }

    @Override
    public void onDestroy() {
       // stopProtectionHeartbeat();
//        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
//        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopHeartbeat();
        releaseWakeLock();

        androidx.lifecycle.ProcessLifecycleOwner.get()
                .getLifecycle()
                .removeObserver(appLifecycleObserver);

        if (screenStateReceiver != null) {
            try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
            screenStateReceiver = null;
        }

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

    private void acquireWakeLock() {
        if (wakeLock == null) return;
        if (prefs == null) return;
        if (!prefs.getBoolean(PREF_USE_WAKELOCK, false)) return;
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
            Timber.tag(TAG).d("WakeLock acquired");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Timber.tag(TAG).d("WakeLock released");
        }
    }

    private void startHeartbeat() {
        if (prefs == null) return;
        if (heartbeatRunning || prefs.getInt(PREF_HEARTBEAT_FREQUENCY, 0) <= 0) return;
        heartbeatRunning = true;
        Thread t = new Thread(() -> {
            while (heartbeatRunning && sessionActive.get() && isContainerPaused) {
                try {
                    Thread.sleep(heartbeat_interval_ms);
                    LogManager.log(TAG, "Heartbeat: Keeping container alive...", getApplicationContext());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Only run the protection sweep while the container is
                // actually paused — no work needed in the foreground.
                if (!heartbeatRunning || !isContainerPaused) {
                    break;
                }
                runOomSweepInternal();
            }
            heartbeatRunning = false;
        }, "SessionHeartbeat");
        t.setDaemon(true);
        t.start();
        heartbeatThread = t;
    }

    private void stopHeartbeat() {
        heartbeatRunning = false;
        Thread t = heartbeatThread;
        if (t != null) {
            t.interrupt();
            heartbeatThread = null;
        }
        LogManager.log(TAG, "Heartbeat stopped", this);
    }

    private void runOomSweep() {
        new Thread(this::runOomSweepInternal, "SessionOomProtection").start();
        LogManager.log(TAG, "OOM protection sweep started", this);
    }

    private void runOomSweepInternal() {
        try {
            ProcessHelper.protectAllWineProcesses();
        } catch (Exception e) {
//            Timber.tag(TAG).e(e, "OOM protection sweep failed");
            LogManager.logE(TAG, "OOM protection sweep failed", e, this);
        }
    }

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

        // 3. MEDIUM PRIORITY: Steam friends (if enabled)
        if (PrefManager.INSTANCE.getChatStayRunningOnExit() && isAppVisible())
            return "Steam chat running in background";

        // 4. LOW PRIORITY: Active store services
        if (!activeComponents.isEmpty()) {
            List<String> names = new ArrayList<>(activeComponents.keySet());
            return names.size() == 1
                    ? names.get(0) + " service is active"
                    : String.join(" and ", names) + " services are active";
        }
        return "WinNative is running in the background";
    }

    private final androidx.lifecycle.DefaultLifecycleObserver appLifecycleObserver =
            new androidx.lifecycle.DefaultLifecycleObserver() {
                @Override
                public void onStart(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                    isAppInBackground = false;
                    LogManager.log(TAG, "App came to foreground (ProcessLifecycleOwner)");
                    updateForegroundState(SessionKeepAliveService.this);
                }

                @Override
                public void onStop(@NonNull androidx.lifecycle.LifecycleOwner owner) {
                    isAppInBackground = true;
                    LogManager.log(TAG, "App went to background (ProcessLifecycleOwner)");
                    updateForegroundState(SessionKeepAliveService.this);
                }
            };

    private static void resetLocalState() {
        sessionActive.set(false);
        isContainerPaused = false;
        isActivityVisible = false;
        synchronized (activeDownloads) { activeDownloads.clear(); }
        activeComponents.clear();
    }

    /**
     * Performs a deep cleanup of native processes and terminates the app PID.
     * This is the shared logic between swiping away and clicking "Exit".
     */
    private static void performDefensiveCleanupAndExit(Context ctx) {
        // Give the activity's own onDestroy → performForcedSessionCleanup a
        // chance to run first; then defensively clean any wine processes that
        // might still be alive, and exit the process so swipe/exit button behaves like the
        // pre-existing "swipe-away closes everything" flow.
        new Thread(() -> {
            try {
                Thread.sleep(1500L);
                cleanUpSession(ctx, "session keep-alive shutdown");
            } catch (Throwable t) {
                LogManager.logW(TAG, "Defensive cleanup failed", t, ctx);
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                if (instance != null) {
                    instance.stopForegroundCompat();
                    instance.stopSelf();
                }
                serviceRunning.set(false);
                // Final kill
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> android.os.Process.killProcess(android.os.Process.myPid()), 500L);
            });
        }, "SessionCleanupAndExit").start();
    }

    private static void cleanUpSession(Context ctx, String reason) {
        if (com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.isBionicHandoffActive()) {
            try {
                boolean kicked = com.winlator.cmod.feature.stores.steam.service.SteamService
                        .Companion.bionicHandoffReleaseAndKickPlayingSessionBlocking(true, 2500L);
                LogManager.logI(TAG, "Task removal/Exit button - Steam cleanup: kickedPlayingSession=" + kicked, ctx);
            } catch (Throwable t) {
                LogManager.logW(TAG, "Task removal/Exit button - Steam cleanup failed", t, ctx);
            }
        }
        ProcessHelper.terminateSessionProcessesAndWait(1500, true);
        ProcessHelper.drainDeadChildren(reason);
    }

    public static void stopAll(Context ctx) {
        stopSession(ctx);
        resetLocalState();

        // Stop Steam specifically
        com.winlator.cmod.feature.stores.steam.service.SteamService.Companion.stop();

        // Finally stop the master service
        SessionKeepAliveService svc = instance;
        if (svc != null) {
            svc.stopForegroundCompat();
            svc.stopSelf();
        }
    }

    // Stop everything and kill the app process.
    public static void closeApp(Context ctx) {
        stopAll(ctx);
        performDefensiveCleanupAndExit(ctx);
    }
}

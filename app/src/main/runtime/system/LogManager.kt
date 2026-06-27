package com.winlator.cmod.runtime.system
import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import com.winlator.cmod.app.config.SettingsConfig
import com.winlator.cmod.shared.io.FileUtils
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private const val TAG = "LogManager"
    private const val LOG_FILE = "app_debug.log"

    private var logcatProcess: Process? = null
    private var appLogProcess: Process? = null
    private var pauseWatchProcess: Process? = null

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    enum class Level(val prefix: String) {
        DEBUG("D"), INFO("I"), WARN("W"), ERROR("E")
    }

    // ── Cached state ──────────────────────────────────────────────────
    //
    // The whole point of this section: nothing below should ever hit
    // SharedPreferences or resolve a URI on a per-log-call basis. Both are
    // read once and kept current by a listener, so a disabled or filtered-out
    // call costs one volatile-field read, not a disk lookup.

    @Volatile private var appContext: Context? = null
    @Volatile var isDebugEnabledCached = false
    @Volatile private var cachedLogsDir: File? = null
    @Volatile private var cachedAllowedTags: Set<String> = emptySet()
    @Volatile private var cachedTextFilters: List<String> = emptyList()

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Cheap, public, and the recommended guard for any genuinely expensive log message. */
    @JvmStatic
    val isDebugEnabled: Boolean
        get() = isDebugEnabledCached

    private fun resolveContext(context: Context?): Context? = context?.applicationContext ?: appContext

    /**
     * Call once, ideally from PluviaApp.onCreate(), so every later call
     * site — including ones with no Context of their own — has a fallback,
     * and so the debug/path-dependent caches above are primed before
     * anything tries to log.
     */
    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        refreshCaches(app)

        if (prefsListener == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    "enable_app_debug", "winlator_path_uri", "app_debug_tags", "app_debug_text_filter" ->
                        refreshCaches(app)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            prefsListener = listener
        }

        Log.d(TAG, "LogManager initialized, context name=${app.javaClass.name}, appContext=$appContext")

        // Set up uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isDebugEnabledCached) {
                logCrash(app, thread, throwable)
            }
            // Call the original handler to maintain default behavior
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Capture previous exit reasons
        if (isDebugEnabledCached && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            logLastExitReasons(app)
        }
    }

    private fun refreshCaches(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        isDebugEnabledCached = prefs.getBoolean("enable_app_debug", false)
        cachedLogsDir = resolveLogsDir(context, prefs)
        cachedAllowedTags = prefs.getString("app_debug_tags", null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
        cachedTextFilters = prefs.getString("app_debug_text_filter", null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    // ── Logs directory ───────────────────────────────────────────────

    @JvmStatic
    fun getLogsDir(context: Context): File {
        /*val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "logs")
        if (!dir.exists()) dir.mkdirs()*/

        /*val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val currentPath = resolvePathString(prefs.getString("winlator_path_uri", null), SettingsConfig.DEFAULT_WINLATOR_PATH, context)

        val dir = File(currentPath, "logs")
        if (!dir.exists()) dir.mkdirs()

        Timber.tag(TAG).d("Winlator path: $ctx")

        return dir*/

        cachedLogsDir?.let { return it }
        val ctx = resolveContext(context) ?: return File(SettingsConfig.DEFAULT_WINLATOR_PATH, "logs").also {
            // No context available anywhere yet (init() never called and none
            // passed in) — fall back without caching, since we can't listen
            // for preference changes without one.
            if (!it.exists()) it.mkdirs()
        }

        // Use the same user-visible WinNative folder as everything else,
        // not the package-private external-files dir, so logs can be
        // browsed/pulled without ADB or root.
        val dir = resolveLogsDir(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        cachedLogsDir = dir

        Timber.tag(TAG).d("Logs dir: $dir")

        return dir
    }

    private fun resolveLogsDir(context: Context, prefs: SharedPreferences): File {
        val pathString = resolvePathString(prefs.getString("winlator_path_uri", null), context)
        val dir = File(pathString, "logs")

        Timber.d("Winnative pathString: $pathString")

        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun resolvePathString(uriStr: String?, context: Context): String {
        if (uriStr.isNullOrEmpty()) return SettingsConfig.DEFAULT_WINLATOR_PATH
        return try {
            FileUtils.getFilePathFromUri(context, Uri.parse(uriStr)) ?: SettingsConfig.DEFAULT_WINLATOR_PATH
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to resolve winlator_path_uri ($uriStr): ${e.message}")
            SettingsConfig.DEFAULT_WINLATOR_PATH
        }
    }

    fun isAnyLoggingEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("enable_wine_debug", false) ||
                prefs.getBoolean("enable_box64_logs", false) ||
                prefs.getBoolean("enable_fexcore_logs", false) ||
                prefs.getBoolean("enable_steam_logs", false) ||
                prefs.getBoolean("enable_input_logs", false) ||
                prefs.getBoolean("enable_download_logs", false) ||
                isDebugEnabledCached
    }

    fun updateLoggingState(context: Context) {
        if (!isAnyLoggingEnabled(context)) stopLogging()
    }

    @JvmStatic
    fun rotateLogsOnAppStart(context: Context) {
        if (!isAnyLoggingEnabled(context)) return
        val logsDir = getLogsDir(context)
        logsDir.listFiles()?.filter { it.name.endsWith(".old.log") }?.forEach { it.delete() }
        // Rename current .log → .old.log
        logsDir.listFiles()?.filter { it.name.endsWith(".log") && !it.name.endsWith(".old.log") }?.forEach { file ->
            file.renameTo(File(logsDir, file.name.replace(".log", ".old.log")))
        }
    }

    @JvmStatic
    fun prepareForNewSession(context: Context) {
        val logsDir = getLogsDir(context)
        logsDir.listFiles()?.filter { it.name.endsWith(".old.log") }?.forEach { it.delete() }
        logsDir.listFiles()?.filter { it.name.endsWith(".log") }?.forEach { it.delete() }
    }

    // ── Wine/Box64 Logcat Capture ────────────────────────────────────

    fun startLogging(context: Context) {
        if (!isAnyLoggingEnabled(context)) {
            stopLogging()
            return
        }

        val logFile = File(getLogsDir(context), "logcat.log")

        try {
            stopLogcat()
            runBlockingLogcatCommand(arrayOf("logcat", "-c"))
            logcatProcess =
                Runtime.getRuntime().exec(
                    arrayOf("logcat", "-f", logFile.absolutePath, "*:D"),
                )
            closeProcessStdin(logcatProcess)
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to start logcat: ${e.message}")
        }
    }

    fun stopLogging() {
        stopLogcat()
        stopAppLogging()
    }

    private fun stopLogcat() {
        try {
            logcatProcess?.let(::destroyProcess)
            logcatProcess = null
        } catch (e: Exception) {
            Timber.tag(TAG).e("Failed to stop logcat: ${e.message}")
        }
    }

    fun clearLogs(context: Context) {
        getLogsDir(context).listFiles()?.forEach { it.delete() }
    }

    @JvmStatic
    fun startAppLogging(context: Context) {
        if (!isDebugEnabledCached) return
        val logFile = File(getLogsDir(context), "application.log")

        try {
            stopAppLogging()
            val pid = android.os.Process.myPid()
            appLogProcess =
                Runtime.getRuntime().exec(
                    arrayOf("logcat", "-f", logFile.absolutePath, "--pid=$pid", "*:W"),
                )
            closeProcessStdin(appLogProcess)
            Timber.i("Application debug logging started (PID=$pid)")
        } catch (e: Exception) {
            Timber.e("Failed to start application logging: ${e.message}")
        }
    }

    @JvmStatic
    fun stopAppLogging() {
        try {
            appLogProcess?.let(::destroyProcess)
            appLogProcess = null
        } catch (e: Exception) {
            Timber.e("Failed to stop application logging: ${e.message}")
        }
    }

    private fun runBlockingLogcatCommand(command: Array<String>) {
        val process = Runtime.getRuntime().exec(command)
        try {
            process.waitFor()
        } finally {
            destroyProcess(process)
        }
    }

    private fun destroyProcess(process: Process) {
        closeProcessStdin(process)
        closeQuietly(process.inputStream)
        closeQuietly(process.errorStream)
        process.destroy()
    }

    private fun closeProcessStdin(process: Process?) {
        closeQuietly(process?.outputStream)
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun getShareableLogFiles(context: Context): Array<File> {
        val logsDir = getLogsDir(context)
        return logsDir
            .listFiles()
            ?.filter {
                it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".old.log") || it.name.endsWith(".txt"))
            }?.toTypedArray() ?: emptyArray()
    }

    /** Total bytes of all shareable log files. */
    @JvmStatic
    fun getShareableLogsSize(context: Context): Long = getShareableLogFiles(context).sumOf { it.length() }

    /** Deletes all shareable log files; returns the count removed. */
    @JvmStatic
    fun deleteShareableLogs(context: Context): Int = getShareableLogFiles(context).count { it.delete() }

    // ── 1. Custom breadcrumbs, callable from anywhere ───────────────
    //
    // Writes directly to disk (open → write → flush → close on every
    // call) instead of going through a buffered writer. This is
    // deliberate: if the process gets killed seconds after this call,
    // an open-but-unflushed buffer would lose exactly the line need.
    // A few extra file opens per session is a non-issue.
    //
    // Message arguments are still evaluated eagerly by the caller
    // for the plain String overloads — for anything expensive to build,
    // guard it with `if (LogManager.isDebugEnabled)`, or use the lambda
    // overload below from Kotlin.

    @JvmStatic @JvmOverloads
    fun log(tag: String, message: String, context: Context? = null) =
        baseLog(Level.DEBUG, tag, message, null, context)

    @JvmStatic @JvmOverloads
    fun logI(tag: String, message: String, context: Context? = null) =
        baseLog(Level.INFO, tag, message, null, context)

    @JvmStatic @JvmOverloads
    fun logW(tag: String, message: String, t: Throwable? = null, context: Context? = null) =
        baseLog(Level.WARN, tag, message, t, context)

    @JvmStatic @JvmOverloads
    fun logE(tag: String, message: String, t: Throwable? = null, context: Context? = null) =
        baseLog(Level.ERROR, tag, message, t, context)

    /**
     * Kotlin-only sugar for genuinely expensive messages: [message] is never
     * invoked at all when debug logging is off. Not exposed to Java —
     * inline functions with function-type parameters don't cross that
     * boundary cleanly; Java callers should use the isDebugEnabled guard
     * instead.
     */
    inline fun log(tag: String, context: Context? = null, message: () -> String) {
        if (!isDebugEnabledCached) return
        baseLog(Level.DEBUG, tag, message(), null, context)
    }

    inline fun logI(tag: String, context: Context? = null, message: () -> String) {
        if (!isDebugEnabledCached) return
        baseLog(Level.INFO, tag, message(), null, context)
    }

    inline fun logW(tag: String, t: Throwable? = null, context: Context? = null, message: () -> String) {
        if (!isDebugEnabledCached) return
        baseLog(Level.WARN, tag, message(), null, context)
    }

    inline fun logE(tag: String, t: Throwable? = null, context: Context? = null, message: () -> String) {
        if (!isDebugEnabledCached) return
        baseLog(Level.ERROR, tag, message(), null, context)
    }

    fun baseLog(level: Level, tag: String, message: String, t: Throwable?, context: Context?) {
        // Mirrors Android Log so this can drop in for Log.* call sites.
        when (level) {
            Level.DEBUG -> Timber.tag(tag).d(message)
            Level.INFO -> Timber.tag(tag).i(message)
            Level.WARN -> if (t != null) Timber.tag(tag).w(t, message) else Timber.tag(tag).w(message)
            Level.ERROR -> if (t != null) Timber.tag(tag).e(t, message) else Timber.tag(tag).e(message)
        }

        if (!isDebugEnabledCached) return
        if (cachedAllowedTags.isNotEmpty() && tag !in cachedAllowedTags) return
        if (cachedTextFilters.isNotEmpty() && cachedTextFilters.none { message.contains(it, ignoreCase = true) }) return

        val ctx = resolveContext(context) ?: return
        val fullMessage = if (t != null) "$message :: ${Log.getStackTraceString(t)}" else message
        appendLine(ctx, LOG_FILE, "${level.prefix}/$tag", fullMessage)
    }

    private fun appendLine(context: Context, fileName: String, level: String, message: String) {
        try {
            val file = File(getLogsDir(context), fileName)
            file.appendText("${timestampFormat.format(Date())} $level: $message\n")
        } catch (e: Exception) {
            Timber.e("Failed to append to $fileName: ${e.message}")
        }
    }

    // ── 2. Pause/resume window capture ───────────────────────────────
    //
    // Brackets exactly the period you care about: screen-lock to
    // screen-unlock. Without android.permission.READ_LOGS granted via
    // adb, this only ever sees your own UID's lines (your own Log.*
    // calls, including whatever you route through log()/logWarn()
    // above) — still useful for confirming your own lifecycle order.
    // WITH the permission granted once over adb, it will also surface
    // system lines like ActivityManager's "Killing <proc> (adj N):
    // <reason>" messages, which is the signal of the OS killing a process.

    @JvmStatic
    fun startPauseWatch(context: Context) {
        if (!isDebugEnabledCached) return

        // Verify READ_LOGS permission at runtime
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS)
            != PackageManager.PERMISSION_GRANTED) {
            logW(TAG, null, context) { "READ_LOGS permission not granted, pause watch may not capture system logs" }
        }

        stopPauseWatch()
        try {
            // Wipe the historical buffer so this file only contains lines from
            // this pause window onward — not hours of unrelated backlog.
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(getLogsDir(context), "pause_$stamp.log")
            appendLine(context, file.name, "I/$TAG", "=== pause window started ===")
            pauseWatchProcess = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat", "-v", "threadtime", "-f", file.absolutePath,
                    "ActivityManager:I", "lmkd:I", "OomAdjuster:I", "ActivityTaskManager:I", "Process:I",
//                    "*:S",    // Silence
                    "*:D",      // Debug [Note: This filter drastically increases the chances that the container will close upon returning to it]
                ),
            )
            closeProcessStdin(pauseWatchProcess)
        } catch (e: Exception) {
//            Timber.e("Failed to start pause watch: ${e.message}")
            logE(TAG, null, context) { "Failed to start pause watch: ${e.message}" }
        }
    }

    @JvmStatic
    fun stopPauseWatch() {
        try {
            pauseWatchProcess?.let(::destroyProcess)
            pauseWatchProcess = null
        } catch (e: Exception) {
            Timber.e("Failed to stop pause watch: ${e.message}")
        }
    }

    // ── 3. Why was the process last killed? ──────────────────────────
    //
    // No special permission needed (API 30+). Call once, early, on
    // every app start — it tells you, after the fact, exactly what
    // ended the previous process: REASON_LOW_MEMORY (real LMK kill),
    // REASON_SIGNALED/REASON_OTHER (often an OEM battery manager),
    // REASON_USER_REQUESTED, REASON_CRASH, etc.

    @JvmStatic @JvmOverloads
    fun logLastExitReasons(context: Context? = null) {
        if (!isDebugEnabledCached) return
        val ctx = resolveContext(context) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos: List<ApplicationExitInfo> =
                am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
            if (infos.isEmpty()) {
                appendLine(ctx, "exit_reasons.log", "I/$TAG", "No historical exit info available")
                return
            }
            for (info in infos) {
                appendLine(
                    ctx, "exit_reasons.log", "I/$TAG",
                    "pid=${info.pid} reason=${info.reason} importance=${info.importance} " +
                            "desc=${info.description} timestamp=${Date(info.timestamp)}",
                )
                if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    try {
                        info.traceInputStream?.use { input ->
                            appendLine(ctx, "exit_reasons.log", "I/$TAG", "Tombstone Data:\n${input.bufferedReader().readText()}")
                        }
                    } catch (e: Exception) {
                        Timber.e("Failed to read tombstone trace: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e("Failed to read exit reasons: ${e.message}")
        }
    }

    @JvmStatic
    fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val fileName = "crash_$timestamp.log"
            val file = File(getLogsDir(context), fileName)

            val crashInfo = buildString {
                appendLine("=== CRASH DETECTED ===")
                appendLine("Thread: ${thread.name} (ID: ${thread.id})")
                appendLine("Timestamp: ${Date()}")
                appendLine("Exception: ${throwable.javaClass.simpleName}")
                appendLine("Message: ${throwable.message}")
                appendLine("\nStack Trace:")
                appendLine(Log.getStackTraceString(throwable))
                appendLine("\n=== END CRASH ===")
            }

            file.writeText(crashInfo)
            Timber.e(throwable, "Crash logged to $fileName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to log crash")
        }
    }
}

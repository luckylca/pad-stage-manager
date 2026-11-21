package dev.stagelab.nativehook.xposed

import android.util.Log
import java.io.File

/**
 * Isolated runtime guard & logger for StageLab Native hooks.
 * Allows instant safe-mode bypass via `/data/local/tmp/stagelab_native_safemode`
 * without rebooting the device.
 */
object HookGuard {
    const val LOG_TAG = "StageLabNativeHook"
    private const val SAFE_MODE_FLAG_PATH = "/data/local/tmp/stagelab_native_safemode"
    private const val CONFIG_FILE_PATH = "/data/local/tmp/stagelab_native_config.properties"

    @Volatile
    private var lastConfigCheckMs = 0L

    @Volatile
    private var cachedEnabled = true

    @Volatile
    private var cachedSafeMode = false

    fun isActive(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastConfigCheckMs > 1_500L) {
            lastConfigCheckMs = now
            refreshFlags()
        }
        return cachedEnabled && !cachedSafeMode
    }

    private fun refreshFlags() {
        try {
            cachedSafeMode = File(SAFE_MODE_FLAG_PATH).exists()
            val cfg = File(CONFIG_FILE_PATH)
            if (cfg.exists() && cfg.canRead()) {
                val lines = cfg.readLines()
                val enabledLine = lines.firstOrNull { it.startsWith("enabled=") }
                cachedEnabled = enabledLine?.substringAfter("=")?.trim()?.toBooleanStrictOrNull() ?: true
            } else {
                cachedEnabled = true
            }
        } catch (_: Throwable) {
            // Never allow guard I/O to break host process.
            cachedSafeMode = false
            cachedEnabled = true
        }
    }

    inline fun safeRun(hookName: String, block: () -> Unit) {
        if (!isActive()) return
        try {
            block()
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Isolated hook failure in $hookName: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    fun info(msg: String) {
        Log.i(LOG_TAG, msg)
    }

    fun warn(msg: String, t: Throwable? = null) {
        if (t != null) Log.w(LOG_TAG, msg, t) else Log.w(LOG_TAG, msg)
    }
}

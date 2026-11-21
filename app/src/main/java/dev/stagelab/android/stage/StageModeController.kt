package dev.stagelab.android.stage

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.stagelab.android.core.stage.BackendKind
import dev.stagelab.android.core.stage.StageRect
import dev.stagelab.android.core.stage.StageRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

enum class StageMode { OFF, STARTING, ON, STOPPING }

data class StageModeState(
    val mode: StageMode = StageMode.OFF,
    val backend: String = "",
    val error: String? = null,
    val needsShizuku: Boolean = false
) {
    val enabled: Boolean get() = mode == StageMode.ON || mode == StageMode.STARTING
}

/**
 * The ON/OFF state machine of the whole product.
 *
 * OFF  -> the control page is the only UI, no polling, no system changes.
 * ON   -> the stage host runs; the privileged service is connected, freeform support is enabled
 *         (with the original settings recorded) and window chrome is live.
 *
 * Stopping never kills the user's applications: every staged task is returned to full screen and
 * keeps running.
 */
object StageModeController {

    private const val TAG = "StageMode"
    private const val PREFS = "stage_mode"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BACKEND = "backend"
    private const val KEY_SETTINGS = "settings_snapshot"

    private val _state = MutableStateFlow(StageModeState())
    val state: StateFlow<StageModeState> = _state.asStateFlow()

    private var appContext: Context? = null
    private val mutex = Mutex()

    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        StageRuntime.attach(context)
        StageWorkspace.attach(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Always start OFF: the mode is only ON after a live privileged connection succeeded, so a
        // stale "enabled" flag can never open an empty workspace.
        _state.value = StageModeState(
            mode = StageMode.OFF,
            backend = prefs.getString(KEY_BACKEND, "").orEmpty()
        )
    }

    fun isEnabled(): Boolean {
        val context = appContext ?: return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** Fire-and-forget entry point for the automation receiver. */
    fun post(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** Connects the privileged backend without entering stage mode (first-run permission flow). */
    suspend fun ensureBackend(preferred: BackendKind? = BackendKind.SHIZUKU): Result<String> {
        if (StageRuntime.isConnected) return Result.success(StageRuntime.state.value.backend)
        val result = StageRuntime.connect(preferred)
        _state.value = _state.value.copy(
            backend = StageRuntime.state.value.backend,
            error = result.exceptionOrNull()?.message
        )
        return result
    }

    suspend fun start(preferred: BackendKind? = null): Result<Unit> = mutex.withLock {
        val context = appContext ?: return Result.failure(IllegalStateException("未初始化"))
        if (_state.value.mode == StageMode.ON) return Result.success(Unit)
        _state.value = _state.value.copy(mode = StageMode.STARTING, error = null)

        val connectResult = if (StageRuntime.isConnected) {
            Result.success(StageRuntime.state.value.backend)
        } else {
            StageRuntime.connect(preferred)
        }
        val backend = StageRuntime.state.value.backend
        if (connectResult.isFailure) {
            val reason = connectResult.exceptionOrNull()?.message ?: "无法连接 Shizuku"
            Log.w(TAG, "start failed: $reason")
            _state.value = _state.value.copy(
                mode = StageMode.OFF,
                error = reason,
                needsShizuku = true
            )
            return Result.failure(connectResult.exceptionOrNull() ?: IllegalStateException("backend"))
        }

        // 1. Record, then apply, the global settings StageLab needs. Nothing else writes them.
        StageSettingsGuard.apply(context)

        // 2. Overlay permission for the window chrome (granted through the shell backend).
        ensureOverlayPermission()
        // 2b. ColorOS freezes the Shizuku manager aggressively, which kills the UserService binding.
        ensureBatteryWhitelist(context)

        // 2c. Register non-reparenting TaskOrganizer for real hardware SurfaceFlinger rounded corners & shadows.
        runCatching {
            val res = StageRuntime.command(org.json.JSONObject().put("cmd", "organizerProbe"))
            Log.i(TAG, "organizerProbe: ${res.optString("result")}")
        }

        // 3. Restore / create the workspace.
        StageWorkspace.attach(context)
        StageWorkspace.reloadSession()
        StageWorkspace.startPolling()

        // 4. Show the stage.
        val intent = Intent(context, StageHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_BACKEND, backend)
            .apply()

        _state.value = _state.value.copy(mode = StageMode.ON, backend = backend, needsShizuku = false)
        Log.i(TAG, "stage mode ON ($backend)")
        Result.success(Unit)
    }

    suspend fun stop(): Result<Unit> = mutex.withLock {
        val context = appContext ?: return Result.failure(IllegalStateException("未初始化"))
        _state.value = _state.value.copy(mode = StageMode.STOPPING)

        // 1. Stop the chrome and the workspace polling before touching tasks.
        StageHostActivity.requestFinish()
        StageWorkspace.stopPolling()

        // 2. Unregister TaskOrganizer and give every staged task back to the system: full screen, focusable, visible.
        if (StageRuntime.isConnected) {
            runCatching { StageRuntime.command(org.json.JSONObject().put("cmd", "organizerUnregister")) }
            runCatching { restoreTasksToFullscreen() }
        }

        // 3. Undo only the settings we changed and only if the user has not changed them since.
        StageSettingsGuard.restore(context)

        // 4. Persist the workspace layout, then leave the mode.
        StageWorkspace.persist()
        runCatching { StageRuntime.disconnect() }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false)
            .apply()
        _state.value = _state.value.copy(mode = StageMode.OFF, error = null)
        Log.i(TAG, "stage mode OFF")
        Result.success(Unit)
    }

    /** Returns staged tasks to full screen so turning the mode off does not destroy them. */
    private suspend fun restoreTasksToFullscreen() {
        val tasks = runCatching { StageRuntime.tasks() }.getOrDefault(emptyList())
        val session = StageWorkspace.session
        val stagedIds = session.windows.values.map { it.taskId }.filter { it > 0 }.toSet()
        val display = displayBounds()
        stagedIds.forEach { taskId ->
            tasks.firstOrNull { it.taskId == taskId } ?: return@forEach
            // Each step is independent: an unsupported call must not skip the others.
            runCatching {
                StageRuntime.command(
                    org.json.JSONObject().put("cmd", "bounds")
                        .put("taskId", taskId)
                        .put("l", display.left).put("t", display.top)
                        .put("r", display.right).put("b", display.bottom)
                )
            }
            runCatching {
                StageRuntime.command(
                    org.json.JSONObject().put("cmd", "windowMode").put("taskId", taskId).put("mode", 1)
                )
            }
            runCatching {
                StageRuntime.command(
                    org.json.JSONObject().put("cmd", "focusable").put("taskId", taskId).put("focusable", true)
                )
            }
        }
        // Leave the user on the most recently used staged application.
        val front = session.visibleWindowsOf(session.activeGroupId).lastOrNull()
            ?: session.windows.values.lastOrNull { it.alive }
        front?.let { window ->
            runCatching {
                StageRuntime.command(
                    org.json.JSONObject().put("cmd", "focus").put("taskId", window.taskId)
                )
            }
        }
    }

    private suspend fun displayBounds(): StageRect {
        val info = runCatching { StageRuntime.runShell("wm size") }.getOrDefault("")
        val match = Regex("(\\d+)x(\\d+)").find(info)
        if (match != null) {
            val w = match.groupValues[1].toInt()
            val h = match.groupValues[2].toInt()
            if (w > 0 && h > 0) return StageRect(0, 0, w, h)
        }
        return StageRect(0, 0, 1680, 2520)
    }

    private suspend fun ensureOverlayPermission() {
        val context = appContext ?: return
        if (android.provider.Settings.canDrawOverlays(context)) return
        runCatching {
            StageRuntime.runShell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        }
        Log.i(TAG, "overlay permission granted via appops")
    }

    /**
     * ColorOS freezes background apps aggressively; with Shizuku frozen the UserService can no
     * longer be re-bound and the stage loses its backend. Whitelisting both packages keeps the
     * privileged channel alive (this is the same thing the user would do in battery settings).
     */
    private suspend fun ensureBatteryWhitelist(context: Context) {
        runCatching {
            StageRuntime.runShell("dumpsys deviceidle whitelist +moe.shizuku.privileged.api")
            StageRuntime.runShell("dumpsys deviceidle whitelist +${context.packageName}")
            StageRuntime.runShell("cmd deviceidle whitelist +moe.shizuku.privileged.api")
            StageRuntime.runShell("cmd deviceidle whitelist +${context.packageName}")
        }
        Log.i(TAG, "battery whitelist applied for Shizuku + StageLab")
    }
}

package dev.stagelab.android.core.stage

import android.content.Context
import android.util.Log
import dev.stagelab.android.core.ipc.AdbChannel
import dev.stagelab.android.core.ipc.ChannelException
import dev.stagelab.android.core.ipc.PrivilegedChannel
import dev.stagelab.android.core.ipc.RootChannel
import dev.stagelab.android.core.shizuku.ShizukuChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

enum class BackendKind(val label: String) {
    SHIZUKU("Shizuku / Shell"),
    ROOT("KernelSU Root"),
    ADB("ADB Shell Service"),
    PUBLIC("普通权限"),
    NONE("未连接")
}

/**
 * App side stage controller. It owns the connection to the privileged engine, the list of managed
 * windows, and the geometry sync loop.
 *
 * The service is a thin executor: the list of windows (what the user opened, its order and role) is
 * authoritative here, which means a Shizuku/root process restart does not destroy the user's stage.
 */
object StageRuntime {

    private const val TAG = "StageRuntime"

    data class ManagedWindow(
        val id: String,
        val packageName: String,
        val title: String,
        val activity: String,
        var taskId: Int,
        var alive: Boolean = true,
        var lastBounds: StageRect? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(StageStateView())
    val state: StateFlow<StageStateView> = _state.asStateFlow()

    private var appContext: Context? = null
    private var channel: PrivilegedChannel? = null
    private var kind: BackendKind = BackendKind.NONE
    private var lastBackend: BackendKind = BackendKind.NONE
    private var pollJob: Job? = null
    private var layoutJob: Job? = null

    private val windows = ArrayList<ManagedWindow>()
    private var viewport: StageLayoutEngine.Layout? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var density = 2.625f
    private var statusInset = 0
    private var navigationInset = 0
    private var applying = false
    private var lastRecoveryAt = 0L

    /** Set while one of our own fullscreen activities (app picker, diagnostics) is on top. */
    @Volatile
    var suppressRecovery: Boolean = false

    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            restoreWindows()
            ensureDefaultViewport()
        }
    }

    /** The picker or a restored process may act before the stage activity reports its size. */
    private fun ensureDefaultViewport() {
        if (viewportWidth > 0) return
        val context = appContext ?: return
        runCatching {
            val metrics = context.resources.displayMetrics
            setViewport(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityValue = metrics.density,
                status = (24 * metrics.density).toInt(),
                navigation = (16 * metrics.density).toInt()
            )
        }
    }

    private fun prefs() = appContext?.getSharedPreferences("stage_windows", Context.MODE_PRIVATE)

    private fun persistWindows() {
        val store = prefs() ?: return
        runCatching {
            val array = org.json.JSONArray()
            synchronized(windows) {
                windows.forEach { window ->
                    array.put(
                        JSONObject()
                            .put("id", window.id)
                            .put("package", window.packageName)
                            .put("activity", window.activity)
                            .put("title", window.title)
                            .put("taskId", window.taskId)
                    )
                }
            }
            store.edit().putString("windows", array.toString()).apply()
        }
    }

    private fun restoreWindows() {
        val store = prefs() ?: return
        val raw = store.getString("windows", null) ?: return
        runCatching {
            val array = org.json.JSONArray(raw)
            synchronized(windows) {
                windows.clear()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    windows += ManagedWindow(
                        id = item.optString("id"),
                        packageName = item.optString("package"),
                        title = item.optString("title"),
                        activity = item.optString("activity"),
                        taskId = item.optInt("taskId"),
                        alive = true
                    )
                }
            }
        }
    }

    val isConnected: Boolean get() = channel != null

    fun setViewport(width: Int, height: Int, densityValue: Float, status: Int, navigation: Int) {
        val changed = width != viewportWidth || height != viewportHeight || densityValue != density
        viewportWidth = width
        viewportHeight = height
        density = densityValue
        statusInset = status
        navigationInset = navigation
        recomputeLayout()
        if (changed && isConnected) {
            scheduleLayoutSync("viewport")
        }
    }

    /** Slot count depends on how many windows are open, so re-run the geometry on every change. */
    private fun recomputeLayout() {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val previewCount = (synchronized(windows) { windows.size } - 1).coerceAtLeast(0)
        viewport = StageLayoutEngine.compute(
            widthPx = viewportWidth,
            heightPx = viewportHeight,
            density = density,
            previewCount = previewCount,
            statusInsetPx = statusInset,
            navigationInsetPx = navigationInset
        )
    }

    // ------------------------------------------------------------------ connection

    suspend fun connect(preferred: BackendKind? = null): Result<String> =
        connectInternal(preferred, restartPolling = true)

    private suspend fun connectInternal(
        preferred: BackendKind?,
        restartPolling: Boolean
    ): Result<String> {
        val context = appContext ?: return Result.failure(ChannelException("Runtime 未初始化"))
        dropChannel()
        val order = when (preferred) {
            BackendKind.SHIZUKU -> listOf(BackendKind.SHIZUKU)
            BackendKind.ROOT -> listOf(BackendKind.ROOT)
            BackendKind.ADB -> listOf(BackendKind.ADB)
            BackendKind.PUBLIC -> listOf(BackendKind.PUBLIC)
            else -> listOf(BackendKind.SHIZUKU, BackendKind.ROOT, BackendKind.ADB, BackendKind.PUBLIC)
        }
        val attempts = ArrayList<String>()
        for (candidate in order) {
            if (candidate == BackendKind.PUBLIC) {
                kind = BackendKind.PUBLIC
                _state.value = StageStateView(
                    connected = false,
                    backend = "普通权限（仅启动应用）",
                    message = "没有可用的特权后端，Stage 窗口托管不可用。",
                    capabilityNotes = listOf(
                        "普通应用无法创建或排列其它应用的任务窗口。",
                        "可以正常启动应用，但无法嵌入实时 Surface。",
                        "启用 Shizuku（Shell）或 KernelSU Root 后即可使用完整 Stage Manager。"
                    )
                )
                return Result.success("普通权限")
            }
            val transport: PrivilegedChannel = when (candidate) {
                BackendKind.SHIZUKU -> ShizukuChannel(context)
                BackendKind.ROOT -> RootChannel(context)
                BackendKind.ADB -> AdbChannel()
                else -> continue
            }
            try {
                val hello = transport.connect()
                channel = transport
                kind = candidate
                lastBackend = candidate
                if (restartPolling) startPolling()
                _state.value = _state.value.copy(
                    connected = true,
                    backend = transport.name,
                    identity = hello.optString("identity"),
                    freeform = hello.optBoolean("freeformEnabled", true),
                    message = null
                )
                refresh()
                scheduleLayoutSync("connect")
                return Result.success(transport.name)
            } catch (t: Throwable) {
                attempts += candidate.label + ": " + (t.message ?: t.javaClass.simpleName)
                runCatching { transport.close() }
            }
        }
        _state.value = _state.value.copy(
            connected = false,
            backend = "未连接",
            message = attempts.joinToString("\n")
        )
        return Result.failure(ChannelException(attempts.joinToString("\n")))
    }

    private fun closeChannel() {
        pollJob?.cancel()
        pollJob = null
        dropChannel()
    }

    /** Drops the transport without touching the polling loop (used by reconnect). */
    private fun dropChannel() {
        runCatching { channel?.close() }
        channel = null
        kind = BackendKind.NONE
    }

    suspend fun disconnect() {
        closeChannel()
        lastBackend = BackendKind.NONE
        _state.value = _state.value.copy(connected = false, backend = "未连接")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var backoff = 500L
            while (isActive) {
                if (channel == null && lastBackend != BackendKind.NONE) {
                    val reconnected = runCatching { connectInternal(lastBackend, false) }.isSuccess
                    if (!reconnected) {
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(5000)
                        continue
                    }
                    backoff = 500L
                }
                runCatching { refresh() }
                delay(450)
            }
        }
    }

    // ------------------------------------------------------------------ operations

    suspend fun openApp(
        packageName: String,
        title: String,
        activity: String = "",
        extraName: String = "",
        extraValue: String = ""
    ): Result<Unit> {
        val transport = channel ?: return Result.failure(ChannelException("未连接特权后端"))
        val layout = viewport ?: return Result.failure(ChannelException("界面尚未就绪"))
        val id = UUID.randomUUID().toString()
        val previewCount = windows.count { it.alive }
        val slot = if (previewCount == 0) {
            layout.main
        } else {
            layout.previews.getOrNull(minOf(previewCount - 1, layout.previews.size - 1)) ?: layout.main
        }
        val command = JSONObject()
            .put("cmd", "open")
            .put("id", id)
            .put("package", packageName)
            .put("activity", activity)
            .put("title", title)
            .put("l", slot.left).put("t", slot.top).put("r", slot.right).put("b", slot.bottom)
        if (extraName.isNotEmpty()) {
            command.put("extraName", extraName).put("extraValue", extraValue)
        }
        return try {
            val reply = call(transport, command)
            if (!reply.optBoolean("ok")) throw ChannelException(reply.optString("error"))
            val data = reply.optJSONObject("data")!!
            val taskId = data.optInt("taskId")
            synchronized(windows) {
                windows += ManagedWindow(
                    id = id,
                    packageName = packageName,
                    title = title,
                    activity = data.optString("activity"),
                    taskId = taskId,
                    alive = true
                )
            }
            persistWindows()
            Log.i(TAG, "opened $packageName as task $taskId")
            recomputeLayout()
            refresh()
            scheduleLayoutSync("open")
            Result.success(Unit)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                message = "打开失败: " + (t.message ?: t.javaClass.simpleName)
            )
            Result.failure(t)
        }
    }

    suspend fun focus(windowId: String): Result<Unit> {
        val transport = channel ?: return Result.failure(ChannelException("未连接特权后端"))
        val window = synchronized(windows) { windows.firstOrNull { it.id == windowId } }
            ?: return Result.failure(ChannelException("窗口不存在"))
        return try {
            val reply = call(transport, JSONObject().put("cmd", "focus").put("taskId", window.taskId))
            if (!reply.optBoolean("ok")) throw ChannelException(reply.optString("error"))
            // Promote immediately: the promoted window becomes the main one.
            synchronized(windows) {
                windows.remove(window)
                windows.add(0, window)
            }
            persistWindows()
            scheduleLayoutSync("focus")
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun close(windowId: String): Result<Unit> {
        val transport = channel
        val window = synchronized(windows) { windows.firstOrNull { it.id == windowId } }
        if (window != null && transport != null) {
            runCatching { call(transport, JSONObject().put("cmd", "close").put("taskId", window.taskId)) }
        }
        synchronized(windows) { windows.remove(window) }
        persistWindows()
        recomputeLayout()
        refresh()
        scheduleLayoutSync("close")
        return Result.success(Unit)
    }

    suspend fun closeAll() {
        val transport = channel
        if (transport != null) {
            runCatching { call(transport, JSONObject().put("cmd", "closeAll")) }
        }
        synchronized(windows) { windows.clear() }
        persistWindows()
        recomputeLayout()
        refresh()
    }

    suspend fun reopen(windowId: String): Result<Unit> {
        val window = synchronized(windows) { windows.firstOrNull { it.id == windowId } }
            ?: return Result.failure(ChannelException("窗口不存在"))
        val transport = channel ?: return Result.failure(ChannelException("未连接特权后端"))
        val layout = viewport ?: return Result.failure(ChannelException("界面尚未就绪"))
        val index = synchronized(windows) { windows.indexOf(window) }
        val slot = if (index <= 0) layout.main else layout.previews.getOrNull(index - 1) ?: layout.main
        return try {
            val reply = call(
                transport,
                JSONObject().put("cmd", "open")
                    .put("id", window.id)
                    .put("package", window.packageName)
                    .put("activity", window.activity)
                    .put("title", window.title)
                    .put("l", slot.left).put("t", slot.top).put("r", slot.right).put("b", slot.bottom)
            )
            if (!reply.optBoolean("ok")) throw ChannelException(reply.optString("error"))
            window.taskId = reply.optJSONObject("data")!!.optInt("taskId")
            window.alive = true
            recomputeLayout()
            refresh()
            scheduleLayoutSync("reopen")
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun windowsSnapshot(): List<ManagedWindow> = synchronized(windows) { windows.toList() }

    /** Fire-and-forget entry point for the automation receiver. */
    fun post(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** Snapshot used by the test script and the diagnostics screen. */
    fun debugStateJson(): String = runCatching {
        val state = _state.value
        val array = org.json.JSONArray()
        state.windows.forEach { window ->
            array.put(
                JSONObject()
                    .put("id", window.id)
                    .put("package", window.packageName)
                    .put("taskId", window.taskId)
                    .put("alive", window.alive)
                    .put("visible", window.visible)
                    .put("focused", window.focused)
                    .put("bounds", window.bounds?.toJson())
            )
        }
        JSONObject()
            .put("backend", state.backend)
            .put("connected", state.connected)
            .put("message", state.message ?: "")
            .put("focusedTaskId", state.focusedTaskId)
            .put("windows", array)
            .toString()
    }.getOrElse { "{\"error\":\"${it.message}\"}" }

    /** Acceptance test: three real, continuously changing tasks at the same time. */
    suspend fun openDemoStage(): Result<Unit> {
        val pkg = appContext?.packageName ?: return Result.failure(ChannelException("Runtime 未初始化"))
        val activity = "dev.stagelab.android.demo.DemoActivity"
        val extra = "dev.stagelab.android.demo.MODE"
        val results = listOf(
            Triple("计数器", "COUNTER", "live-counter"),
            Triple("动画", "MOTION", "live-motion"),
            Triple("视频", "VIDEO", "live-video")
        ).map { (title, mode, tag) ->
            openApp(pkg, title, activity, extra, mode)
        }
        return results.firstOrNull { it.isFailure } ?: Result.success(Unit)
    }

    fun hasHiddenWindows(): Boolean = _state.value.windows.any { it.alive && !it.visible }

    /**
     * Launches one of our own utility screens (app picker, diagnostics) as a floating freeform
     * task above the stage. A normal fullscreen activity would be covered by the staged windows,
     * because those are separate higher tasks.
     */
    suspend fun openTransientScreen(activityName: String, title: String): Result<Unit> {
        val transport = channel ?: return Result.failure(ChannelException("未连接特权后端"))
        val width = if (viewportWidth > 0) viewportWidth else 1680
        val height = if (viewportHeight > 0) viewportHeight else 2520
        val screenW = (width * 0.66f).toInt()
        val screenH = (height * 0.74f).toInt()
        val left = (width - screenW) / 2
        val top = (height - screenH) / 2
        return try {
            val reply = call(
                transport,
                JSONObject().put("cmd", "open")
                    .put("id", "transient-" + UUID.randomUUID())
                    .put("package", appContext?.packageName ?: "dev.stagelab.android")
                    .put("activity", activityName)
                    .put("title", title)
                    .put("transient", true)
                    .put("l", left).put("t", top)
                    .put("r", left + screenW).put("b", top + screenH)
            )
            if (!reply.optBoolean("ok")) throw ChannelException(reply.optString("error"))
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Raw engine diagnostics used by the capability matrix screen. */
    suspend fun diagnostics(): Map<String, String> {
        val transport = channel ?: return emptyMap()
        val reply = call(transport, JSONObject().put("cmd", "diagnostics"))
        if (!reply.optBoolean("ok")) return mapOf("error" to reply.optString("error"))
        val data = reply.optJSONObject("data") ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        data.keys().forEach { key -> result[key] = data.optString(key) }
        return result
    }

    suspend fun organizerProbe(): String {
        val transport = channel ?: return "未连接特权后端"
        val reply = call(transport, JSONObject().put("cmd", "organizerProbe"))
        return if (reply.optBoolean("ok")) {
            reply.optJSONObject("data")?.optString("result") ?: reply.toString()
        } else {
            "失败: " + reply.optString("error")
        }
    }

    suspend fun runShell(command: String): String {
        val transport = channel ?: return "未连接特权后端"
        val reply = call(transport, JSONObject().put("cmd", "shell").put("command", command))
        return if (reply.optBoolean("ok")) reply.optJSONObject("data")?.optString("result").orEmpty()
        else "失败: " + reply.optString("error")
    }

    // ------------------------------------------------------------------ state + geometry

    suspend fun refresh() {
        val transport = channel ?: return
        val reply = try {
            call(transport, JSONObject().put("cmd", "state"))
        } catch (t: Throwable) {
            // The privileged process can die (Shizuku restart, root service exit). Drop the dead
            // transport; the polling loop reconnects with backoff.
            val previous = if (kind != BackendKind.NONE) kind else lastBackend
            _state.value = _state.value.copy(reconnecting = true)
            dropChannel()
            val reconnected = connectInternal(previous, false)
            _state.value = _state.value.copy(reconnecting = false)
            if (reconnected.isFailure) {
                _state.value = _state.value.copy(
                    connected = false,
                    message = "特权服务断开: " + (t.message ?: "unknown")
                )
                return
            }
            call(channel ?: return, JSONObject().put("cmd", "state"))
        }
        if (!reply.optBoolean("ok")) {
            _state.value = _state.value.copy(message = "状态读取失败: " + reply.optString("error"))
            return
        }
        val data = reply.optJSONObject("data") ?: return
        val tasksJson = data.optJSONArray("tasks")
        val taskList = ArrayList<JSONObject>()
        if (tasksJson != null) {
            for (index in 0 until tasksJson.length()) taskList += tasksJson.getJSONObject(index)
        }
        val focusedTaskId = taskList.firstOrNull { it.optBoolean("focused") }?.optInt("taskId") ?: -1

        val views = ArrayList<StageWindowView>()
        var needsLayout = false
        synchronized(windows) {
            for (window in windows) {
                var task = taskList.firstOrNull { it.optInt("taskId") == window.taskId }
                if (task == null) {
                    // Recover a task after a service/process restart. Only freeform tasks whose
                    // top activity matches the one we launched may be adopted: matching by package
                    // alone would hijack the stage host or an unrelated window of the same app.
                    task = taskList.firstOrNull { candidate ->
                        candidate.optInt("windowingMode") == 5 &&
                            candidate.optString("topActivity").contains(window.activity) &&
                            candidate.optString("topActivity").startsWith(window.packageName + "/")
                    }
                    if (task != null) {
                        window.taskId = task.optInt("taskId")
                        needsLayout = true
                    }
                }
                val alive = task != null
                if (alive != window.alive) {
                    window.alive = alive
                }
                views += StageWindowView(
                    id = window.id,
                    packageName = window.packageName,
                    title = window.title,
                    activity = window.activity,
                    taskId = window.taskId,
                    bounds = StageRect.from(task?.optJSONObject("bounds")),
                    focused = task?.optBoolean("focused") ?: false,
                    visible = task?.optBoolean("visible") ?: false,
                    alive = alive
                )
            }

            val promoted = windows.firstOrNull { it.alive && it.taskId == focusedTaskId }
            if (promoted != null && windows.firstOrNull() !== promoted) {
                windows.remove(promoted)
                windows.add(0, promoted)
                needsLayout = true
                persistWindows()
            }
        }

        _state.value = _state.value.copy(
            connected = true,
            windows = views,
            focusedTaskId = focusedTaskId,
            lastRefreshMs = System.currentTimeMillis()
        )

        // A fullscreen activity of our own (app picker, diagnostics) hides every freeform task.
        // When we come back to the stage, re-focus the hidden windows so their live surfaces
        // return, ending with the main window focused.
        val focusedTopActivity = taskList.firstOrNull { it.optBoolean("focused") }
            ?.optString("topActivity").orEmpty()
        val stageOwnsFocus = focusedTopActivity.startsWith(appContext?.packageName ?: "\u0000")
        val hiddenStageWindows = views.count { it.alive && !it.visible }
        if (!suppressRecovery && stageOwnsFocus && hiddenStageWindows > 0 &&
            System.currentTimeMillis() - lastRecoveryAt > 1500
        ) {
            lastRecoveryAt = System.currentTimeMillis()
            scope.launch { runCatching { ensureVisible("auto") } }
        }

        if (needsLayout || focusedTaskId != _state.value.focusedTaskId) {
            scheduleLayoutSync("focus-change")
        }
    }

    /**
     * Makes every managed window visible again by focusing it (WM hides freeform tasks that were
     * covered by a fullscreen task). The main window is focused last so it keeps the input focus.
     */
    suspend fun ensureVisible(reason: String): Boolean {
        val transport = channel ?: return false
        val snapshot = synchronized(windows) { windows.filter { it.alive } }
        if (snapshot.isEmpty()) return false
        var restored = false
        for (window in snapshot.asReversed()) {
            val reply = runCatching {
                call(transport, JSONObject().put("cmd", "focus").put("taskId", window.taskId))
            }.getOrNull()
            if (reply?.optBoolean("ok") == true) restored = true
            delay(70)
        }
        if (restored) {
            synchronized(windows) { windows.forEach { it.lastBounds = null } }
            applyLayout("ensureVisible:$reason")
        }
        return restored
    }

    private fun scheduleLayoutSync(reason: String) {
        layoutJob?.cancel()
        layoutJob = scope.launch {
            delay(60)
            runCatching { applyLayout(reason) }.onFailure { Log.w(TAG, "layout sync failed: ${it.message}") }
        }
    }

    private suspend fun applyLayout(reason: String) {
        val transport = channel ?: return
        val layout = viewport ?: return
        if (applying) return
        applying = true
        try {
            val snapshot = synchronized(windows) { windows.toList() }
            snapshot.forEachIndexed { index, window ->
                if (!window.alive) return@forEachIndexed
                val target = if (index == 0) {
                    layout.main
                } else {
                    layout.previews.getOrNull(index - 1)
                }
                if (target == null) {
                    // No rail slot left (landscape fits fewer previews). Park the task behind the
                    // main window: it stays alive and keeps its state, but is fully covered.
                    if (window.lastBounds != layout.main) {
                        val reply = call(
                            transport,
                            JSONObject().put("cmd", "bounds")
                                .put("taskId", window.taskId)
                                .put("l", layout.main.left).put("t", layout.main.top)
                                .put("r", layout.main.right).put("b", layout.main.bottom)
                        )
                        if (reply.optBoolean("ok")) window.lastBounds = layout.main
                        Log.i(TAG, "parked ${window.title} behind main")
                    }
                    return@forEachIndexed
                }
                if (window.lastBounds == target) return@forEachIndexed
                val reply = call(
                    transport,
                    JSONObject().put("cmd", "bounds")
                        .put("taskId", window.taskId)
                        .put("l", target.left).put("t", target.top)
                        .put("r", target.right).put("b", target.bottom)
                )
                if (reply.optBoolean("ok")) {
                    window.lastBounds = target
                    val actual = StageRect.from(reply.optJSONObject("data")?.optJSONObject("bounds"))
                    if (actual != null && (actual.width != target.width || actual.height != target.height)) {
                        Log.w(
                            TAG,
                            "WM adjusted ${window.title}: requested ${target.width}x${target.height} " +
                                "got ${actual.width}x${actual.height}"
                        )
                    }
                } else {
                    Log.w(TAG, "bounds failed for ${window.title}: ${reply.optString("error")}")
                }
            }
            Log.d(TAG, "layout applied ($reason) windows=${snapshot.size}")
        } finally {
            applying = false
        }
    }

    suspend fun forceLayout() {
        synchronized(windows) { windows.forEach { it.lastBounds = null } }
        applyLayout("manual")
    }

    private suspend fun call(transport: PrivilegedChannel, command: JSONObject): JSONObject =
        transport.call(command)

    fun backendKind(): BackendKind = kind

}

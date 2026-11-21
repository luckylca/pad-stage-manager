package dev.stagelab.android.core.privileged

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Thin, reflection based wrapper over the platform task/window binder interfaces.
 *
 * Everything here runs inside the privileged service process (Shell UID 2000 via Shizuku,
 * or UID 0 via KernelSU). The class never assumes a specific Android version: every symbol is
 * resolved by name at runtime and reported through [diagnostics].
 */
class TaskMutations {

    private val atm: Any by lazy { activityTaskManager() }
    private val windowOrganizer: Any by lazy { windowOrganizerController() }

    private val taskTokens = ConcurrentHashMap<Int, Any>()

    /** Last reflection failure, surfaced by the diagnostics screen. */
    var lastError: String? = null
        private set

    fun describeError(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return root.javaClass.simpleName + ": " + (root.message ?: "no message")
    }

    fun taskToken(taskId: Int): Any? = taskTokens[taskId]

    /** Returns a token, refreshing the task list once if a task was not seen yet. */
    internal fun requireToken(taskId: Int): Any {
        taskTokens[taskId]?.let { return it }
        refreshTokens()
        return taskTokens[taskId] ?: throw IllegalStateException("No token for task $taskId")
    }

    fun refreshTokens() {
        runCatching {
            listTasks().forEach { task ->
                task.token?.let { taskTokens[task.taskId] = it }
            }
        }
    }

    // ------------------------------------------------------------------ queries

    data class TaskEntry(
        val taskId: Int,
        val rootTaskId: Int,
        val displayId: Int,
        val windowingMode: Int,
        val isVisible: Boolean,
        val isFocused: Boolean,
        val topActivity: String?,
        val baseActivity: String?,
        val bounds: Rect?,
        val numActivities: Int,
        val token: Any?
    )

    fun listTasks(): List<TaskEntry> {
        val raw = try {
            HiddenApi.invoke(atm, "getTasks", Int.MAX_VALUE, false, true, 0)
        } catch (t: Throwable) {
            lastError = "getTasks(4): " + describeError(t)
            try {
                HiddenApi.invoke(atm, "getTasks", Int.MAX_VALUE, false, 0)
            } catch (second: Throwable) {
                lastError += " | getTasks(3): " + describeError(second)
                return emptyList()
            }
        } as? List<*> ?: return emptyList()
        lastError = null

        return raw.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            runCatching {
                val taskInfoClass = item.javaClass
                val taskId = HiddenApi.fieldOrNull(taskInfoClass, "taskId")?.getInt(item) ?: -1
                val rootTaskId = HiddenApi.fieldOrNull(taskInfoClass, "rootTaskId")?.getInt(item) ?: taskId
                val displayId = HiddenApi.fieldOrNull(taskInfoClass, "displayId")?.getInt(item) ?: 0
                val numActivities = HiddenApi.fieldOrNull(taskInfoClass, "numActivities")?.getInt(item) ?: 0
                val topActivity = HiddenApi.fieldOrNull(taskInfoClass, "topActivity")?.get(item) as? ComponentName
                val baseActivity = HiddenApi.fieldOrNull(taskInfoClass, "baseActivity")?.get(item) as? ComponentName
                val isVisible = HiddenApi.fieldOrNull(taskInfoClass, "isVisible")?.getBoolean(item) ?: false
                val isFocused = HiddenApi.fieldOrNull(taskInfoClass, "isFocused")?.getBoolean(item) ?: false
                val token = HiddenApi.fieldOrNull(taskInfoClass, "token")?.get(item)
                if (token != null) taskTokens[taskId] = token

                var bounds: Rect? = null
                var windowingMode = 0
                val config = HiddenApi.fieldOrNull(taskInfoClass, "configuration")?.get(item)
                if (config != null) {
                    val wc = HiddenApi.fieldOrNull(config.javaClass, "windowConfiguration")?.get(config)
                    if (wc != null) {
                        bounds = HiddenApi.invoke(wc, "getBounds") as? Rect
                        windowingMode = (HiddenApi.invoke(wc, "getWindowingMode") as? Int) ?: 0
                    }
                }
                TaskEntry(
                    taskId = taskId,
                    rootTaskId = rootTaskId,
                    displayId = displayId,
                    windowingMode = windowingMode,
                    isVisible = isVisible,
                    isFocused = isFocused,
                    topActivity = topActivity?.flattenToShortString(),
                    baseActivity = baseActivity?.flattenToShortString(),
                    bounds = bounds,
                    numActivities = numActivities,
                    token = token
                )
            }.getOrNull()
        }
    }

    fun focusedTaskId(): Int = listTasks().firstOrNull { it.isFocused }?.taskId ?: -1

    // ------------------------------------------------------------------ mutations

    /**
     * Starts [intent] as a new freeform task with the given initial bounds.
     *
     * Primary path: IActivityTaskManager.startActivityAsUser with a launch bounds option.
     * Fallback: the `am` shell command, which is what ADB based tooling uses on OEM builds
     * that harden the binder call.
     */
    fun startFreeformTask(intent: Intent, bounds: Rect): LaunchOutcome {
        val options = try {
            val opts = ActivityOptions.makeBasic()
            opts.setLaunchBounds(bounds)
            HiddenApi.invoke(opts, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
            opts.toBundle()
        } catch (t: Throwable) {
            Bundle().apply { putParcelable(KEY_LAUNCH_BOUNDS, bounds) }
        }

        val before = listTasks().map { it.taskId }.toSet()

        val binderResult = runCatching { startActivityBinder(intent, options) }
        if (binderResult.isSuccess) {
            val taskId = awaitNewTask(before, intent)
            if (taskId > 0) return LaunchOutcome(true, taskId, binderResult.getOrNull() ?: "started via binder")
        }

        val execResult = runCatching { startActivityShell(intent, bounds) }
        val taskId = awaitNewTask(before, intent)
        if (taskId > 0) {
            return LaunchOutcome(true, taskId, execResult.getOrNull() ?: "started via am")
        }

        // The app refused a new task (singleTask launcher / singleInstance). Reuse its live task
        // instead of reporting a failure: the Dock's "open" semantics are "show it", not "spawn".
        val existing = findTaskFor(intent)
        if (existing > 0) {
            return LaunchOutcome(true, existing, "reused existing task")
        }

        return LaunchOutcome(
            false,
            -1,
            "binder=" + binderResult.exceptionOrNull()?.message +
                " am=" + execResult.exceptionOrNull()?.message
        )
    }

    /** Existing task whose top activity matches [intent]'s component. */
    fun findTaskFor(intent: Intent): Int {
        val component = intent.component?.flattenToShortString() ?: return -1
        return listTasks()
            .sortedByDescending { if (it.isFocused) 2 else if (it.isVisible) 1 else 0 }
            .firstOrNull { task ->
                task.topActivity == component || task.baseActivity == component
            }?.taskId ?: -1
    }

    private fun awaitNewTask(before: Set<Int>, intent: Intent, timeoutMs: Long = 2500): Int {
        val component = intent.component?.flattenToShortString()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val tasks = listTasks()
            val fresh = tasks.filter { it.taskId !in before }
            val matching = fresh.firstOrNull { task ->
                component == null || task.topActivity == component || task.baseActivity == component
            }
            if (matching != null) return matching.taskId
            try {
                Thread.sleep(80)
            } catch (_: InterruptedException) {
                return -1
            }
        }
        return -1
    }

    fun taskBounds(taskId: Int): Rect? = runCatching {
        HiddenApi.invoke(atm, "getTaskBounds", taskId) as? Rect
    }.getOrNull()

    fun resizeTask(taskId: Int, bounds: Rect): String {
        val direct = runCatching {
            HiddenApi.invoke(atm, "resizeTask", taskId, bounds, RESIZE_MODE_USER)
            "resizeTask($taskId,$bounds)"
        }
        if (direct.isSuccess) return direct.getOrThrow()

        val token = requireToken(taskId)
        return applyWct { wct: Any ->
            HiddenApi.invoke(wct, "setBounds", token, bounds)
        }.let { "wct.setBounds($taskId,$bounds)" }
    }

    fun focusTask(taskId: Int): String {
        // IActivityTaskManager.setFocusedTask is what `am task focus` uses; it also brings a hidden
        // freeform task back into the visible set.
        val focused = runCatching {
            HiddenApi.invoke(atm, "setFocusedTask", taskId)
            "setFocusedTask($taskId)"
        }
        if (focused.isSuccess) return focused.getOrThrow()

        val token = taskTokens[taskId]
        if (token != null) {
            runCatching {
                applyWct { wct: Any -> HiddenApi.invoke(wct, "startTask", taskId, null) }
                return "wct.startTask($taskId)"
            }
            runCatching {
                applyWct { wct: Any -> HiddenApi.invoke(wct, "reorder", token, true) }
                return "wct.reorder($taskId)"
            }
        }
        val moved = runCatching {
            HiddenApi.invoke(atm, "moveTaskToFront", null, "com.android.shell", taskId, 0, null)
            "moveTaskToFront($taskId)"
        }
        return moved.getOrElse { error ->
            throw IllegalStateException("focus failed: ${error.message}")
        }
    }

    fun removeTask(taskId: Int): String {
        val removed = runCatching {
            val result = HiddenApi.invoke(atm, "removeTask", taskId)
            if (result == false) throw IllegalStateException("removeTask returned false")
            "removeTask($taskId)"
        }
        if (removed.isSuccess) return removed.getOrThrow()

        val token = taskTokens[taskId]
        if (token != null) {
            runCatching {
                applyWct { wct: Any -> HiddenApi.invoke(wct, "removeTask", token) }
                return "wct.removeTask($taskId)"
            }
        }
        return runCatching {
            HiddenApi.invoke(atm, "removeTask", taskId)
            "removeTask($taskId)"
        }.getOrElse { error -> throw IllegalStateException("remove failed: ${error.message}") }
    }

    fun setTaskVisible(taskId: Int, visible: Boolean): String {
        val token = requireToken(taskId)
        return applyWct { wct: Any -> HiddenApi.invoke(wct, "setHidden", token, !visible) }
            .let { "wct.setHidden($taskId,${!visible})" }
    }

    fun setWindowingMode(taskId: Int, windowingMode: Int): String {
        val token = requireToken(taskId)
        return applyWct { wct: Any -> HiddenApi.invoke(wct, "setWindowingMode", token, windowingMode) }
            .let { "wct.setWindowingMode($taskId,$windowingMode)" }
    }

    /** Parked (rail preview) windows must not take focus when the user taps them. */
    fun setTaskFocusable(taskId: Int, focusable: Boolean): String {
        val token = requireToken(taskId)
        return applyWct { wct: Any -> HiddenApi.invoke(wct, "setFocusable", token, focusable) }
            .let { "wct.setFocusable($taskId,$focusable)" }
    }

    /**
     * Moves a task to the bottom of its display area. The stage host (a full screen, opaque task
     * launched before the stage windows) then covers it: minimized windows stay alive but invisible.
     */
    fun sendToBack(taskId: Int): String {
        val token = requireToken(taskId)
        val reordered = runCatching {
            applyWct { wct: Any -> HiddenApi.invoke(wct, "reorder", token, false) }
            "wct.reorder($taskId,false)"
        }
        if (reordered.isSuccess) return reordered.getOrThrow()
        // Fallback: hide through WindowContainerTransaction.
        return applyWct { wct: Any -> HiddenApi.invoke(wct, "setHidden", token, true) }
            .let { "wct.setHidden($taskId,true)" }
    }

    /**
     * Raises the StageHostActivity task above parked background freeform tasks so only the active
     * group's windows (raised immediately afterwards) remain visible on screen.
     */
    fun bringHostAboveParked(hostComponent: String): String {
        val host = listTasks().firstOrNull { task ->
            task.topActivity?.contains(hostComponent) == true ||
                task.baseActivity?.contains(hostComponent) == true
        } ?: return "host not found"
        val token = host.token ?: taskTokens[host.taskId]
        if (token != null) {
            runCatching {
                applyWct { wct: Any -> HiddenApi.invoke(wct, "reorder", token, true) }
                return "wct.reorder(host=${host.taskId},true)"
            }
        }
        return runCatching { focusTask(host.taskId) }.getOrElse { "host focus failed: ${it.message}" }
    }

    data class SnapshotResult(val width: Int, val height: Int, val jpegBase64: String)

    var lastSnapshotError: String? = null
        private set

    @Volatile
    private var cachedRawFrame: RawFrame? = null
    @Volatile
    private var cachedScreenAtMs: Long = 0L

    private class RawFrame(
        val width: Int,
        val height: Int,
        val pixelOffset: Int,
        val bytes: ByteArray
    )

    /**
     * Captures a scaled JPEG thumbnail of [taskId].
     *
     * Strategy:
     *  1. Try platform TaskSnapshot API (direct declared-method lookup on IActivityTaskManager).
     *  2. If unavailable on Android 16/17 OEM builds and the task is currently visible on screen,
     *     capture the raw uncompressed framebuffer via Shell UID `screencap` (NO `-p` PNG encode,
     *     ~5x faster than `screencap -p`!) and subsample-crop only the task's exact bounds.
     */
    fun captureTaskSnapshot(taskId: Int, maxDim: Int = 360): SnapshotResult? {
        lastSnapshotError = null
        captureViaPlatformSnapshot(taskId, maxDim)?.let { return it }
        return captureViaRawScreencapCrop(taskId, maxDim)
    }

    private fun captureViaPlatformSnapshot(taskId: Int, maxDim: Int): SnapshotResult? {
        val snapshot = fetchRawTaskSnapshot(taskId) ?: return null
        val buffer = runCatching {
            HiddenApi.invoke(snapshot, "getHardwareBuffer") as? HardwareBuffer
        }.getOrNull() ?: runCatching {
            HiddenApi.fieldOrNull(snapshot.javaClass, "mSnapshot")?.get(snapshot) as? HardwareBuffer
        }.getOrNull() ?: return null
        val colorSpace = runCatching {
            HiddenApi.invoke(snapshot, "getColorSpace") as? ColorSpace
        }.getOrNull() ?: runCatching {
            HiddenApi.fieldOrNull(snapshot.javaClass, "mColorSpace")?.get(snapshot) as? ColorSpace
        }.getOrNull()

        var hwBitmap: Bitmap? = null
        var swBitmap: Bitmap? = null
        return try {
            hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
            swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            encodeThumbnail(swBitmap, maxDim)
        } catch (t: Throwable) {
            lastSnapshotError = describeError(t)
            null
        } finally {
            runCatching { swBitmap?.recycle() }
            runCatching { hwBitmap?.recycle() }
            runCatching { buffer.close() }
        }
    }

    private fun captureViaRawScreencapCrop(taskId: Int, maxDim: Int): SnapshotResult? {
        val entry = listTasks().firstOrNull { it.taskId == taskId }
        val rect = entry?.bounds ?: taskBounds(taskId)
        if (entry == null || !entry.isVisible || rect == null || rect.width() <= 4 || rect.height() <= 4) {
            lastSnapshotError = "task $taskId not visible for raw screencap"
            return null
        }
        val frame = acquireRawFrame() ?: run {
            lastSnapshotError = "raw screencap returned null"
            return null
        }
        val left = rect.left.coerceIn(0, (frame.width - 1).coerceAtLeast(0))
        val top = rect.top.coerceIn(0, (frame.height - 1).coerceAtLeast(0))
        val right = rect.right.coerceIn(left + 1, frame.width)
        val bottom = rect.bottom.coerceIn(top + 1, frame.height)
        val srcW = right - left
        val srcH = bottom - top
        if (srcW <= 4 || srcH <= 4) return null

        val scale = (maxDim.toFloat() / max(srcW, srcH).toFloat()).coerceAtMost(1f)
        val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)
        val pixels = IntArray(dstW * dstH)
        val raw = frame.bytes
        val stride = frame.width * 4
        val baseOff = frame.pixelOffset

        for (dy in 0 until dstH) {
            val sy = top + (dy * srcH) / dstH
            val rowOff = baseOff + sy * stride
            val dstRow = dy * dstW
            for (dx in 0 until dstW) {
                val sx = left + (dx * srcW) / dstW
                val idx = rowOff + sx * 4
                if (idx + 3 < raw.size) {
                    val r = raw[idx].toInt() and 0xFF
                    val g = raw[idx + 1].toInt() and 0xFF
                    val b = raw[idx + 2].toInt() and 0xFF
                    pixels[dstRow + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        var bmp: Bitmap? = null
        return try {
            bmp = Bitmap.createBitmap(pixels, dstW, dstH, Bitmap.Config.ARGB_8888)
            val stream = ByteArrayOutputStream(16 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            SnapshotResult(dstW, dstH, b64)
        } catch (t: Throwable) {
            lastSnapshotError = "raw crop failed: ${describeError(t)}"
            null
        } finally {
            runCatching { bmp?.recycle() }
        }
    }

    private val rawFrameLock = Any()

    private fun acquireRawFrame(): RawFrame? = synchronized(rawFrameLock) {
        val now = System.currentTimeMillis()
        val existing = cachedRawFrame
        if (existing != null && now - cachedScreenAtMs < 600L) {
            return existing
        }
        try {
            val process = ProcessBuilder("/system/bin/screencap").start()
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            if (bytes.size < 16) return null
            val w = readLeInt(bytes, 0)
            val h = readLeInt(bytes, 4)
            if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
            val expectedPixels = w * h * 4
            val headerSize = bytes.size - expectedPixels
            if (headerSize != 12 && headerSize != 16) {
                lastSnapshotError = "unexpected raw header=$headerSize for ${w}x${h}"
                return null
            }
            val frame = RawFrame(w, h, headerSize, bytes)
            cachedRawFrame = frame
            cachedScreenAtMs = System.currentTimeMillis()
            frame
        } catch (t: Throwable) {
            lastSnapshotError = "raw screencap: ${describeError(t)}"
            null
        }
    }

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun encodeThumbnail(source: Bitmap, maxDim: Int): SnapshotResult? {
        val srcW = source.width
        val srcH = source.height
        if (srcW <= 0 || srcH <= 0) return null
        var scaled: Bitmap? = null
        return try {
            val scale = maxDim.toFloat() / max(srcW, srcH).toFloat()
            val outBitmap = if (scale < 1f) {
                val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
                val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(source, dstW, dstH, true).also { scaled = it }
            } else {
                source
            }
            val stream = ByteArrayOutputStream(16 * 1024)
            outBitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            SnapshotResult(outBitmap.width, outBitmap.height, b64)
        } finally {
            runCatching { scaled?.recycle() }
        }
    }

    private fun fetchRawTaskSnapshot(taskId: Int): Any? {
        val cls = atm.javaClass
        val attempts: List<Pair<java.lang.reflect.Method?, Array<Any?>>> = listOf(
            HiddenApi.methodOrNull(cls, "getTaskSnapshot", Integer.TYPE, java.lang.Boolean.TYPE) to arrayOf(taskId, false),
            HiddenApi.methodOrNull(cls, "getTaskSnapshot", Integer.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE) to arrayOf(taskId, false, true),
            HiddenApi.methodOrNull(cls, "takeTaskSnapshot", Integer.TYPE, java.lang.Boolean.TYPE) to arrayOf(taskId, true),
            HiddenApi.methodOrNull(cls, "takeTaskSnapshot", Integer.TYPE) to arrayOf(taskId)
        )
        for ((method, args) in attempts) {
            if (method == null) continue
            val result = runCatching {
                method.isAccessible = true
                method.invoke(atm, *args)
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    fun enableFreeformGlobally(): String {
        val results = ArrayList<String>()
        for ((key, value) in listOf(
            "enable_freeform_support" to "1",
            "force_resizable_activities" to "1"
        )) {
            results += runCatching { shell("settings put global $key $value") }.getOrElse { "settings failed: ${it.message}" }
        }
        return results.joinToString("; ")
    }

    // ------------------------------------------------------------------ diagnostics

    fun diagnostics(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["identity"] = RuntimeIdentity.describe()
        out["sdk"] = android.os.Build.VERSION.SDK_INT.toString()
        out["fingerprint"] = android.os.Build.FINGERPRINT
        out["manageActivityTasks"] = runCatching {
            if (RuntimeIdentity.permissionGranted("android.permission.MANAGE_ACTIVITY_TASKS")) "granted" else "denied"
        }.getOrElse { "unavailable" }
        out["activityTaskManager"] = runCatching {
            "ok, getTasks=" + listTasks().size
        }.getOrElse { "FAILED: ${it.javaClass.simpleName}: ${it.message}" }
        out["resizeTask"] = runCatching {
            val method = atm.javaClass.methods.firstOrNull { it.name == "resizeTask" }
            method?.parameterTypes?.joinToString(",") { it.simpleName } ?: "missing"
        }.getOrElse { "unavailable" }
        out["windowOrganizer"] = runCatching {
            val methods = windowOrganizer.javaClass.methods.map { it.name }.distinct()
            "ok: " + methods.joinToString(",")
        }.getOrElse { "FAILED: ${it.message}" }
        out["activityOptions"] = runCatching {
            val opt = ActivityOptions.makeBasic()
            "setLaunchBounds=" + (HiddenApi.methodOrNull(opt.javaClass, "setLaunchBounds", Rect::class.java) != null) +
                " setLaunchWindowingMode=" + (HiddenApi.methodOrNull(opt.javaClass, "setLaunchWindowingMode", Integer.TYPE) != null)
        }.getOrElse { "unavailable: ${it.message}" }
        out["taskOrganizer"] = runCatching {
            val cls = Class.forName("android.window.TaskOrganizer")
            "present; registerOrganizer=" + (HiddenApi.methodOrNull(cls, "registerOrganizer") != null) +
                "; createRootTask=" + (HiddenApi.methodOrNull(
                    cls, "createRootTask", Integer.TYPE, Integer.TYPE,
                    HiddenApi.classOrThrow("android.os.IBinder"), java.lang.Boolean.TYPE
                ) != null)
        }.getOrElse { "missing: ${it.message}" }
        out["shellExec"] = runCatching { shell("id") }.getOrElse { "failed: ${it.message}" }
        return out
    }

    // ------------------------------------------------------------------ internals

    fun applyWct(builder: (Any) -> Unit): Unit {
        val wctClass = HiddenApi.classOrThrow("android.window.WindowContainerTransaction")
        val wct = wctClass.getDeclaredConstructor().newInstance()
        builder(wct)
        val applyMethod = windowOrganizer.javaClass.methods.firstOrNull {
            it.name == "applyTransaction" && it.parameterCount == 1
        } ?: throw IllegalStateException("IWindowOrganizerController.applyTransaction missing")
        applyMethod.invoke(windowOrganizer, wct)
    }

    private fun startActivityBinder(intent: Intent, options: Bundle): String {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        val candidates = atm.javaClass.methods.filter { method ->
            method.name == "startActivityAsUser" &&
                method.parameterTypes.any { it.name == "android.content.Intent" } &&
                method.parameterTypes.any { it.name == "android.os.Bundle" }
        }
        val errors = StringBuilder()
        for (method in candidates.sortedBy { it.parameterCount }) {
            val args = buildArgs(method.parameterTypes, intent, options, flags) ?: continue
            try {
                method.isAccessible = true
                method.invoke(atm, *args)
                return "startActivityAsUser(args${method.parameterCount})"
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                errors.append(cause.javaClass.simpleName).append(':').append(cause.message).append(" | ")
            }
        }
        throw IllegalStateException("startActivityAsUser failed: $errors")
    }

    private fun buildArgs(
        types: Array<Class<*>>,
        intent: Intent,
        options: Bundle,
        flags: Int
    ): Array<Any?>? {
        val args = arrayOfNulls<Any?>(types.size)
        var stringIndex = 0
        var intIndex = 0
        for ((index, type) in types.withIndex()) {
            when {
                type.name == "android.content.Intent" -> args[index] = intent
                type.name == "android.os.Bundle" -> args[index] = options
                type.name == "android.app.IApplicationThread" -> args[index] = null
                type.name == "android.os.IBinder" -> args[index] = null
                type.name == "android.app.ProfilerInfo" -> args[index] = null
                type.name == "java.lang.String" -> {
                    args[index] = when (stringIndex) {
                        0 -> "com.android.shell"
                        else -> null
                    }
                    stringIndex++
                }
                type == Integer.TYPE -> {
                    args[index] = when (intIndex) {
                        0 -> 0          // requestCode
                        1 -> flags      // launch flags
                        else -> 0       // userId
                    }
                    intIndex++
                }
                else -> return null
            }
        }
        return args
    }

    private fun startActivityShell(intent: Intent, bounds: Rect): String {
        val component = intent.component ?: throw IllegalArgumentException("component required")
        val cmd = "${component.packageName}/${component.className}"
        val output = shell("am start -n $cmd --windowingMode $WINDOWING_MODE_FREEFORM --activity-multiple-task")
        if (output.contains("Error") || output.contains("Exception")) {
            throw IllegalStateException(output.trim())
        }
        return output.trim()
    }

    fun shell(command: String): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return text
    }

    private fun activityTaskManager(): Any {
        val cls = HiddenApi.classOrThrow("android.app.ActivityTaskManager")
        return HiddenApi.methodOrThrow(cls, "getService").invoke(null)
            ?: throw IllegalStateException("ActivityTaskManager.getService() returned null")
    }

    private fun windowOrganizerController(): Any {
        val binder = HiddenApi.systemService("window")
        val stub = HiddenApi.classOrNull("android.window.IWindowOrganizerController\$Stub")
            ?: HiddenApi.classOrThrow("android.window.IWindowOrganizerController")
        return HiddenApi.methodOrThrow(stub, "asInterface", HiddenApi.classOrThrow("android.os.IBinder"))
            .invoke(null, binder)
            ?: throw IllegalStateException("IWindowOrganizerController.asInterface returned null")
    }

    data class LaunchOutcome(val success: Boolean, val taskId: Int, val detail: String)

    companion object {
        const val WINDOWING_MODE_FREEFORM = 5
        const val RESIZE_MODE_USER = 1
        private const val KEY_LAUNCH_BOUNDS = "android.activity.launchBounds"
    }
}

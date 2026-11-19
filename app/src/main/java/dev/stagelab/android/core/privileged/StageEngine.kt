package dev.stagelab.android.core.privileged

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The single privileged engine. It is hosted by:
 *
 *  * the Shizuku UserService process (Shell UID 2000), and
 *  * the KernelSU / ADB `app_process` service (UID 0 or 2000).
 *
 * Both transports call [handle] with the same JSON command protocol, so there is exactly one
 * implementation of task control to reason about and to test.
 */
object StageEngine {

    private val tasks = TaskMutations()
    private val windows = ConcurrentHashMap<String, WindowRecord>()
    private val events = ArrayList<String>(128)

    data class WindowRecord(
        val id: String,
        var taskId: Int,
        var packageName: String,
        var activityName: String,
        var title: String,
        var bounds: Rect
    )

    @Synchronized
    fun handle(raw: String): String {
        val reply = JSONObject()
        try {
            val command = JSONObject(raw)
            val name = command.optString("cmd")
            val data = when (name) {
                "hello" -> hello()
                "state" -> state()
                "diagnostics" -> JSONObject(tasks.diagnostics().toMap())
                "settings" -> JSONObject().put("result", tasks.enableFreeformGlobally())
                "open" -> open(command)
                "bounds" -> bounds(command)
                "focus" -> focus(command)
                "close" -> close(command)
                "hide" -> setHidden(command, true)
                "show" -> setHidden(command, false)
                "focusable" -> setFocusable(command)
                "windowMode" -> setWindowMode(command)
                "toBack" -> sendToBack(command)
                "closeAll" -> closeAll()
                "freeformOn" -> JSONObject().put("result", tasks.enableFreeformGlobally())
                "organizerProbe" -> organizerProbe()
                "organizerUnregister" -> organizerUnregister()
                "events" -> JSONObject().put("events", JSONArray(events.toList()))
                "shell" -> JSONObject().put("result", tasks.shell(command.optString("command")))
                "ping" -> JSONObject().put("pong", System.currentTimeMillis())
                else -> throw IllegalArgumentException("Unknown command: $name")
            }
            reply.put("ok", true).put("data", data)
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            reply.put("ok", false)
                .put("error", root.javaClass.simpleName + ": " + (root.message ?: "no message"))
            record("ERROR " + root.javaClass.simpleName + ": " + root.message)
        }
        return reply.toString()
    }

    // ------------------------------------------------------------------ commands

    private fun hello(): JSONObject {
        // Note: `hello` deliberately does NOT touch global settings. The Stage Manager mode
        // controller records the original values and is the only place allowed to change them.
        tasks.refreshTokens()
        return JSONObject()
            .put("identity", RuntimeIdentity.describe())
            .put("uid", RuntimeIdentity.uid())
            .put("selinux", RuntimeIdentity.selinuxContext())
            .put("manageTasks", RuntimeIdentity.permissionGranted("android.permission.MANAGE_ACTIVITY_TASKS"))
            .put("freeformEnabled", true)
            .put("version", PROTOCOL_VERSION)
    }

    private fun state(): JSONObject {
        val taskArray = JSONArray()
        val allTasks = tasks.listTasks()
        allTasks.forEach { task ->
            taskArray.put(
                JSONObject()
                    .put("taskId", task.taskId)
                    .put("rootTaskId", task.rootTaskId)
                    .put("displayId", task.displayId)
                    .put("windowingMode", task.windowingMode)
                    .put("visible", task.isVisible)
                    .put("focused", task.isFocused)
                    .put("topActivity", task.topActivity ?: "")
                    .put("numActivities", task.numActivities)
                    .put("bounds", boundsJson(task.bounds))
            )
        }
        val windowArray = JSONArray()
        windows.values.forEach { window ->
            val live = allTasks.firstOrNull { it.taskId == window.taskId }
            windowArray.put(
                JSONObject()
                    .put("id", window.id)
                    .put("taskId", window.taskId)
                    .put("package", window.packageName)
                    .put("activity", window.activityName)
                    .put("title", window.title)
                    .put("bounds", boundsJson(window.bounds))
                    .put("alive", live != null)
                    .put("visible", live?.isVisible ?: false)
                    .put("focused", live?.isFocused ?: false)
                    .put("windowingMode", live?.windowingMode ?: 0)
            )
        }
        return JSONObject()
            .put("tasks", taskArray)
            .put("windows", windowArray)
            .put("identity", RuntimeIdentity.describe())
            .put("uid", RuntimeIdentity.uid())
            .put("protocol", PROTOCOL_VERSION)
    }

    private fun open(command: JSONObject): JSONObject {
        val id = command.getString("id")
        val packageName = command.getString("package")
        val activityName = command.optString("activity")
        val rect = rectFrom(command)
        val title = command.optString("title").ifBlank { packageName }
        val resolved = resolveLaunchComponent(packageName, activityName)
            ?: throw IllegalStateException("No launchable activity for $packageName")

        val intent = Intent().apply {
            component = resolved
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val extraName = command.optString("extraName")
            if (extraName.isNotEmpty()) {
                putExtra(extraName, command.optString("extraValue"))
            }
        }
        val outcome = tasks.startFreeformTask(intent, rect)
        if (!outcome.success) {
            throw IllegalStateException("Launch failed: " + outcome.detail)
        }
        val record = WindowRecord(id, outcome.taskId, packageName, resolved.className, title, rect)
        if (!command.optBoolean("transient")) {
            windows[id] = record
        }
        tasks.refreshTokens()
        // The first launch can land at the default freeform rect; enforce the requested one.
        runCatching { tasks.resizeTask(outcome.taskId, rect) }
        record("OPEN $id task=${outcome.taskId} $packageName $rect")
        return JSONObject()
            .put("id", id)
            .put("taskId", outcome.taskId)
            .put("package", packageName)
            .put("activity", resolved.className)
            .put("bounds", boundsJson(rect))
            .put("detail", outcome.detail)
            .put("reused", outcome.detail.startsWith("reused"))
    }

    private fun bounds(command: JSONObject): JSONObject {
        val taskId = resolveTaskId(command)
        val rect = rectFrom(command)
        tasks.resizeTask(taskId, rect)
        val actual = tasks.taskBounds(taskId) ?: rect
        windows.values.firstOrNull { it.taskId == taskId }?.bounds = actual
        return JSONObject().put("taskId", taskId).put("bounds", boundsJson(actual))
    }

    private fun focus(command: JSONObject): JSONObject {
        val taskId = resolveTaskId(command)
        tasks.focusTask(taskId)
        return JSONObject().put("taskId", taskId)
    }

    private fun close(command: JSONObject): JSONObject {
        val taskId = resolveTaskId(command)
        tasks.removeTask(taskId)
        windows.entries.firstOrNull { it.value.taskId == taskId }?.let { windows.remove(it.key) }
        record("CLOSE task=$taskId")
        return JSONObject().put("taskId", taskId)
    }

    private fun setHidden(command: JSONObject, hidden: Boolean): JSONObject {
        val taskId = resolveTaskId(command)
        tasks.setTaskVisible(taskId, !hidden)
        return JSONObject().put("taskId", taskId).put("hidden", hidden)
    }

    private fun setFocusable(command: JSONObject): JSONObject {
        val taskId = resolveTaskId(command)
        val focusable = command.optBoolean("focusable", true)
        tasks.setTaskFocusable(taskId, focusable)
        return JSONObject().put("taskId", taskId).put("focusable", focusable)
    }

    private fun setWindowMode(command: JSONObject): JSONObject {
        val taskId = resolveTaskId(command)
        val mode = command.getInt("mode")
        tasks.setWindowingMode(taskId, mode)
        return JSONObject().put("taskId", taskId).put("mode", mode)
    }

    /**
     * Sends a task behind the stage host, which is how "minimize" is implemented: the task keeps
     * running and keeps its state, but the opaque host covers it.
     */
    private fun sendToBack(command: JSONObject): JSONObject {
        val taskId = resolveTaskId(command)
        tasks.sendToBack(taskId)
        return JSONObject().put("taskId", taskId)
    }

    private fun closeAll(): JSONObject {
        val closed = JSONArray()
        windows.values.toList().forEach { window ->
            runCatching { tasks.removeTask(window.taskId) }
            closed.put(window.taskId)
            windows.remove(window.id)
        }
        record("CLOSE_ALL")
        return JSONObject().put("closed", closed)
    }

    private fun organizerProbe(): JSONObject = JSONObject().put("result", StageTaskOrganizerHolder.probe())

    private fun organizerUnregister(): JSONObject = JSONObject().put("result", StageTaskOrganizerHolder.unregister())

    // ------------------------------------------------------------------ helpers

    private fun resolveTaskId(command: JSONObject): Int {
        if (command.has("taskId")) return command.getInt("taskId")
        val id = command.optString("id")
        return windows[id]?.taskId ?: throw IllegalArgumentException("Unknown window id: $id")
    }

    private fun rectFrom(command: JSONObject): Rect = Rect(
        command.getInt("l"),
        command.getInt("t"),
        command.getInt("r"),
        command.getInt("b")
    )

    private fun boundsJson(rect: Rect?): JSONObject = JSONObject()
        .put("l", rect?.left ?: 0)
        .put("t", rect?.top ?: 0)
        .put("r", rect?.right ?: 0)
        .put("b", rect?.bottom ?: 0)

    private fun resolveLaunchComponent(packageName: String, activityName: String): ComponentName? {
        if (activityName.isNotBlank()) return ComponentName(packageName, activityName)
        val output = tasks.shell("cmd package resolve-activity --brief $packageName")
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') }
        if (line != null) {
            val pkg = line.substringBefore('/')
            val cls = line.substringAfter('/')
            val fullCls = if (cls.startsWith(".")) pkg + cls else cls
            return ComponentName(pkg, fullCls)
        }
        return null
    }

    private fun record(event: String) {
        synchronized(events) {
            events += System.currentTimeMillis().toString() + " " + event
            while (events.size > 120) events.removeAt(0)
        }
    }

    const val PROTOCOL_VERSION = 2
}

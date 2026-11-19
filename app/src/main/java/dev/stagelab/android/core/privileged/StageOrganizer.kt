package dev.stagelab.android.core.privileged

import android.os.IBinder
import android.view.SurfaceControl
import android.window.TaskOrganizer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TaskOrganizer used by the diagnostics probe and by the experimental embedded-surface mode.
 *
 * It is intentionally separate from the freeform stage controller: registering an organizer makes
 * this process responsible for the tasks handed to it, so the two modes must never be mixed
 * silently.
 */
class StageOrganizer : TaskOrganizer() {

    private val events = CopyOnWriteArrayList<String>()

    @Volatile
    var initialTaskCount: Int = 0

    @Volatile
    var appearedCount: Int = 0

    fun createRoot(displayId: Int, windowingMode: Int, cookie: IBinder): String {
        val three = HiddenApi.methodOrNull(
            TaskOrganizer::class.java,
            "createRootTask",
            Integer.TYPE,
            Integer.TYPE,
            IBinder::class.java
        )
        val four = HiddenApi.methodOrNull(
            TaskOrganizer::class.java,
            "createRootTask",
            Integer.TYPE,
            Integer.TYPE,
            IBinder::class.java,
            java.lang.Boolean.TYPE
        )
        return try {
            when {
                four != null -> four.invoke(this, displayId, windowingMode, cookie, true)
                three != null -> three.invoke(this, displayId, windowingMode, cookie)
                else -> return "createRootTask missing on this build"
            }
            "createRootTask(display=$displayId, mode=$windowingMode) requested"
        } catch (t: Throwable) {
            "createRootTask failed: " + TaskMutations().describeError(t)
        }
    }

    fun register(): String = try {
        val result = registerOrganizer()
        initialTaskCount = result.size
        "REGISTERED initialTasks=$initialTaskCount"
    } catch (t: Throwable) {
        "REGISTRATION_FAILED " + TaskMutations().describeError(t)
    }

    fun unregister(): String = try {
        unregisterOrganizer()
        "UNREGISTERED"
    } catch (t: Throwable) {
        "UNREGISTER_FAILED " + TaskMutations().describeError(t)
    }

    override fun onTaskAppeared(taskInfo: android.app.ActivityManager.RunningTaskInfo, leash: SurfaceControl) {
        appearedCount += 1
        record("APPEARED " + describe(taskInfo))
    }

    override fun onTaskVanished(taskInfo: android.app.ActivityManager.RunningTaskInfo) {
        record("VANISHED task=${taskInfo.taskId}")
    }

    override fun onTaskInfoChanged(taskInfo: android.app.ActivityManager.RunningTaskInfo) {
        record("CHANGED " + describe(taskInfo))
    }

    private fun describe(taskInfo: android.app.ActivityManager.RunningTaskInfo): String {
        val mode = runCatching { HiddenApi.fieldOrNull(taskInfo.javaClass, "windowingMode")?.getInt(taskInfo) }.getOrNull()
        val bounds = runCatching { HiddenApi.fieldOrNull(taskInfo.javaClass, "bounds")?.get(taskInfo) }.getOrNull()
        return "task=${taskInfo.taskId} mode=$mode bounds=$bounds"
    }

    fun snapshot(): String =
        if (events.isEmpty()) "No TaskOrganizer callbacks received yet." else events.joinToString("\n")

    private fun record(event: String) {
        events += System.currentTimeMillis().toString() + " " + event
        while (events.size > 80) events.removeAt(0)
    }
}

object StageTaskOrganizerHolder {
    private var organizer: StageOrganizer? = null

    @Synchronized
    fun probe(): String {
        organizer?.let { return "ALREADY_REGISTERED initialTasks=${it.initialTaskCount}\n${it.snapshot()}" }
        val candidate = StageOrganizer()
        val result = candidate.register()
        if (result.startsWith("REGISTERED")) organizer = candidate
        return result + "\n" + candidate.snapshot()
    }

    @Synchronized
    fun unregister(): String {
        val current = organizer ?: return "NOT_REGISTERED"
        val result = current.unregister()
        organizer = null
        return result
    }
}

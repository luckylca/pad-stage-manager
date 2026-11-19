package dev.stagelab.android.core.shizuku

import android.app.ActivityManager
import android.view.SurfaceControl
import android.window.TaskOrganizer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal TaskOrganizer observer. It deliberately does not reparent, resize,
 * reorder, or otherwise mutate any task or SurfaceControl.
 */
class StageTaskOrganizer : TaskOrganizer() {
    private val events = CopyOnWriteArrayList<String>()

    var initialTaskCount: Int = 0
        private set

    fun setInitialTaskCount(count: Int) {
        initialTaskCount = count
    }

    override fun onTaskAppeared(taskInfo: ActivityManager.RunningTaskInfo, leash: SurfaceControl) {
        record("APPEARED taskId=${taskInfo.taskId}")
    }

    override fun onTaskVanished(taskInfo: ActivityManager.RunningTaskInfo) {
        record("VANISHED taskId=${taskInfo.taskId}")
    }

    override fun onTaskInfoChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        record("INFO_CHANGED taskId=${taskInfo.taskId}")
    }

    override fun onBackPressedOnTaskRoot(taskInfo: ActivityManager.RunningTaskInfo) {
        record("BACK_PRESSED taskId=${taskInfo.taskId}")
    }

    fun snapshot(): String = if (events.isEmpty()) {
        "No TaskOrganizer callbacks received yet."
    } else {
        events.joinToString(separator = "\n")
    }

    private fun record(event: String) {
        events += "${System.currentTimeMillis()} $event"
        while (events.size > MAX_EVENTS) {
            events.removeAt(0)
        }
    }

    private companion object {
        const val MAX_EVENTS = 80
    }
}

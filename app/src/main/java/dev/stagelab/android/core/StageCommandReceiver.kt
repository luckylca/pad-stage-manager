package dev.stagelab.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.stagelab.android.core.stage.BackendKind
import dev.stagelab.android.stage.StageModeController
import dev.stagelab.android.stage.StageWorkspace
import org.json.JSONArray
import org.json.JSONObject

/**
 * Automation hook used by `scripts/device-smoke-test.ps1` (and ad-hoc device checks).
 *
 * It drives exactly the same controllers the UI uses, so a passing script means the product paths
 * work, not a parallel test-only implementation.
 */
class StageCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("cmd") ?: return
        StageModeController.attach(context)
        StageWorkspace.attach(context)
        Log.i(TAG, "command=$command")
        when (command) {
            "start" -> StageModeController.post {
                val kind = intent.getStringExtra("backend")
                    ?.let { runCatching { BackendKind.valueOf(it) }.getOrNull() }
                StageModeController.start(kind)
            }
            "stop" -> StageModeController.post { StageModeController.stop() }
            "state" -> Log.i(TAG, "state=" + stateJson())
            "open" -> {
                val pkg = intent.getStringExtra("package") ?: return
                val label = intent.getStringExtra("title") ?: pkg
                val activity = intent.getStringExtra("activity").orEmpty()
                val extraName = intent.getStringExtra("extraName").orEmpty()
                val extraValue = intent.getStringExtra("extraValue").orEmpty()
                StageModeController.post {
                    StageWorkspace.openApp(pkg, label, activity, null, extraName, extraValue)
                }
            }
            "dockTap" -> {
                val pkg = intent.getStringExtra("package") ?: return
                val label = intent.getStringExtra("title") ?: pkg
                val activity = intent.getStringExtra("activity").orEmpty()
                StageModeController.post {
                    StageWorkspace.activateOrOpenApp(pkg, label, activity)
                }
            }
            "railDragIn" -> {
                val groupId = intent.getStringExtra("groupId") ?: StageWorkspace.state.value.groups.firstOrNull()?.id ?: return
                StageModeController.post {
                    StageWorkspace.addGroupToActiveGroup(groupId, null)
                }
            }
            "demo" -> StageModeController.post { openDemo(context.packageName) }
            "group" -> StageModeController.post {
                StageWorkspace.createGroup()
                StageWorkspace.syncGeometry("test-new-group")
            }
            "rail" -> StageWorkspace.setRailVisible(intent.getBooleanExtra("visible", true))
            "closeGroup" -> StageModeController.post { closeActiveGroup() }
            "reset" -> StageModeController.post { StageWorkspace.resetSession() }
            else -> Log.w(TAG, "unknown command: $command")
        }
    }

    private suspend fun openDemo(pkg: String) {
        val activity = "dev.stagelab.android.demo.DemoActivity"
        val extra = "dev.stagelab.android.demo.MODE"
        StageWorkspace.openApp(pkg, "计数器", activity, null, extra, "COUNTER")
        StageWorkspace.openApp(pkg, "动画", activity, null, extra, "MOTION")
        StageWorkspace.openApp(pkg, "视频", activity, null, extra, "VIDEO")
    }

    private suspend fun closeActiveGroup() {
        val session = StageWorkspace.session
        session.windowsOf(session.activeGroupId).toList().forEach { window ->
            StageWorkspace.closeWindow(window.id)
        }
    }

    private fun stateJson(): String = runCatching {
        val state = StageWorkspace.state.value
        val windows = JSONArray()
        state.windows.forEach { window ->
            windows.put(
                JSONObject()
                    .put("id", window.id)
                    .put("package", window.packageName)
                    .put("taskId", window.taskId)
                    .put("alive", window.alive)
                    .put("focused", window.focused)
                    .put("l", window.rect.left).put("t", window.rect.top)
                    .put("r", window.rect.right).put("b", window.rect.bottom)
            )
        }
        val groups = JSONArray()
        state.groups.forEach { group ->
            groups.put(JSONObject().put("id", group.id).put("title", group.title).put("windows", group.windowCount))
        }
        JSONObject()
            .put("mode", StageModeController.state.value.mode.name)
            .put("backend", state.backend)
            .put("activeGroup", state.activeGroupId)
            .put("railVisible", state.railVisible)
            .put("groups", groups)
            .put("windows", windows)
            .toString()
    }.getOrElse { "{\"error\":\"${it.message}\"}" }

    companion object {
        private const val TAG = "StageCmd"

    }
}

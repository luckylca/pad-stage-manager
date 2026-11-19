package dev.stagelab.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.stagelab.android.core.stage.BackendKind
import dev.stagelab.android.core.stage.StageRuntime

/**
 * Automation hook used by the device test scripts (`scripts/device-smoke-test.ps1`).
 *
 * It is intentionally tiny: it forwards a small, explicit command set to [StageRuntime]. Keeping
 * it in the app means the same code paths the UI uses are exercised by the automated tests, instead
 * of a parallel test-only implementation.
 */
class StageCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("cmd") ?: return
        StageRuntime.attach(context)
        Log.i(TAG, "command=$command")
        when (command) {
            "demo" -> StageRuntime.post { StageRuntime.openDemoStage() }
            "closeAll" -> StageRuntime.post { StageRuntime.closeAll() }
            "reflow" -> StageRuntime.post { StageRuntime.forceLayout() }
            "open" -> {
                val pkg = intent.getStringExtra("package") ?: return
                val title = intent.getStringExtra("title") ?: pkg
                val activity = intent.getStringExtra("activity").orEmpty()
                val extraName = intent.getStringExtra("extraName").orEmpty()
                val extraValue = intent.getStringExtra("extraValue").orEmpty()
                StageRuntime.post { StageRuntime.openApp(pkg, title, activity, extraName, extraValue) }
            }
            "connect" -> {
                val kind = intent.getStringExtra("backend")
                    ?.let { runCatching { BackendKind.valueOf(it) }.getOrNull() }
                StageRuntime.post { StageRuntime.connect(kind) }
            }
            "state" -> Log.i(TAG, "state=" + StageRuntime.debugStateJson())
            else -> Log.w(TAG, "unknown command: $command")
        }
    }

    private companion object {
        const val TAG = "StageCmd"
    }
}

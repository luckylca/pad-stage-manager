package dev.stagelab.android.control

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.stagelab.android.core.shizuku.ShizukuChannel
import dev.stagelab.android.core.stage.BackendKind
import dev.stagelab.android.core.stage.StageRuntime
import dev.stagelab.android.stage.StageHostActivity
import dev.stagelab.android.stage.StageMode
import dev.stagelab.android.stage.StageModeController
import dev.stagelab.android.ui.StageLabTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * The product entry point: a single Stage Manager switch.
 *
 * Turning it on connects the privileged backend (Shizuku first), records and applies the global
 * settings that freeform windows need, restores the saved workspace and opens [StageHostActivity].
 * Turning it off returns the staged tasks to full screen instead of killing them.
 */
class ControlActivity : ComponentActivity() {

    private var busy by mutableStateOf(false)

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_SHIZUKU) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    busy = false
                    toggle()
                } else {
                    busy = false
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        StageModeController.attach(this)
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }

        setContent {
            StageLabTheme {
                val state by StageModeController.state.collectAsState()
                ControlScreen(
                    state = state,
                    busy = busy,
                    onToggle = { toggle() },
                    onRequestShizuku = { requestShizuku() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning to the app while the mode is ON: bring the workspace back if it was closed.
        val state = StageModeController.state.value
        if (state.mode == StageMode.ON && StageRuntime.isConnected) {
            StageHostActivity.start(this)
        }
    }

    private fun toggle() {
        if (busy) return
        val mode = StageModeController.state.value.mode
        when (mode) {
            StageMode.ON -> {
                busy = true
                lifecycleScope.launch {
                    StageModeController.stop()
                    busy = false
                }
            }
            StageMode.OFF -> {
                busy = true
                lifecycleScope.launch {
                    // First contact: connect quietly, and only surface the Shizuku prompt if that
                    // actually failed because the server is missing or unauthorised.
                    val backend = StageModeController.ensureBackend(BackendKind.SHIZUKU)
                    if (backend.isFailure) {
                        if (ShizukuChannel.isServerRunning() && !ShizukuChannel.hasPermission()) {
                            requestShizuku()
                        } else {
                            val root = StageModeController.ensureBackend(BackendKind.ROOT)
                            if (root.isFailure) {
                                busy = false
                            } else {
                                startStage()
                            }
                        }
                    } else {
                        startStage()
                    }
                }
            }
            StageMode.STARTING, StageMode.STOPPING -> Unit
        }
    }

    private fun startStage() {
        lifecycleScope.launch {
            val result = StageModeController.start(BackendKind.SHIZUKU)
            busy = false
            if (result.isFailure) {
                // Root is the documented fallback, never the default.
                StageModeController.start(BackendKind.ROOT)
            }
        }
    }

    private fun requestShizuku() {
        if (!ShizukuChannel.isServerRunning()) {
            busy = false
            return
        }
        if (ShizukuChannel.hasPermission()) {
            startStage()
            return
        }
        busy = true
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        ShizukuChannel.requestPermission(REQUEST_SHIZUKU)
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_SHIZUKU = 7201
    }
}

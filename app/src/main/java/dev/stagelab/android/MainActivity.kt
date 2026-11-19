package dev.stagelab.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.lifecycleScope
import dev.stagelab.android.core.stage.BackendKind
import dev.stagelab.android.core.stage.StageRuntime
import dev.stagelab.android.shizuku.ShizukuProbeActivity
import dev.stagelab.android.ui.AppPickerActivity
import dev.stagelab.android.ui.DiagnosticsActivity
import dev.stagelab.android.ui.StageLabTheme
import dev.stagelab.android.ui.StageManagerScreen
import kotlinx.coroutines.launch

/**
 * Stage host. This activity is the stage floor: real application windows are separate system tasks
 * composited above it. The window is deliberately non focusable so tapping the dock never raises
 * the stage above the hosted applications and never steals IME focus from them.
 */
class MainActivity : ComponentActivity() {

    private val shizukuPermissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE &&
                grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                lifecycleScope.launch { StageRuntime.connect(BackendKind.SHIZUKU) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Never take input focus: staged tasks keep the IME and key focus. Touch still works.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        StageRuntime.attach(this)
        runCatching {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }

        setContent {
            StageLabTheme {
                val state by StageRuntime.state.collectAsState()
                val density = LocalDensity.current
                val scope = rememberCoroutineScope()
                val statusTop = WindowInsets.statusBars.getTop(density)
                val navBottom = WindowInsets.navigationBars.getBottom(density)
                val densityValue = remember { density.density }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .onSizeChanged { size ->
                            StageRuntime.setViewport(
                                width = size.width,
                                height = size.height,
                                densityValue = densityValue,
                                status = statusTop,
                                navigation = navBottom
                            )
                        }
                ) {
                    StageManagerScreen(
                        state = state,
                        density = densityValue,
                        onConnect = { kind ->
                            scope.launch {
                                if (kind == BackendKind.SHIZUKU) {
                                    requestShizukuAndConnect()
                                } else {
                                    StageRuntime.connect(kind)
                                }
                            }
                        },
                        onOpenPicker = {
                            scope.launch {
                                StageRuntime.openTransientScreen(
                                    AppPickerActivity::class.java.name,
                                    "选择应用"
                                )
                            }
                        },
                        onFocus = { id -> scope.launch { StageRuntime.focus(id) } },
                        onClose = { id -> scope.launch { StageRuntime.close(id) } },
                        onReopen = { id -> scope.launch { StageRuntime.reopen(id) } },
                        onCloseAll = { scope.launch { StageRuntime.closeAll() } },
                        onReflow = { scope.launch { StageRuntime.forceLayout() } },
                        onDemo = { scope.launch { StageRuntime.openDemoStage() } },
                        onOpenDiagnostics = {
                            scope.launch {
                                StageRuntime.openTransientScreen(
                                    DiagnosticsActivity::class.java.name,
                                    "诊断"
                                )
                            }
                        },
                        onShowHelp = {}
                    )
                }

                LaunchedEffect(state.windows.any { it.alive }) {
                    // With staged windows on screen the stage must never take focus (that would
                    // raise it above them). On an empty stage there is nothing to protect, and a
                    // focusable window keeps accessibility, Back and the IME working normally.
                    val hasWindows = state.windows.any { it.alive }
                    if (hasWindows) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    }
                }

                LaunchedEffect(Unit) {
                    if (!StageRuntime.isConnected) {
                        val shizuku = StageRuntime.connect(BackendKind.SHIZUKU)
                        if (shizuku.isFailure) {
                            val adb = StageRuntime.connect(BackendKind.ADB)
                            if (adb.isFailure) {
                                StageRuntime.connect(BackendKind.ROOT)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StageRuntime.suppressRecovery = false
        lifecycleScope.launch {
            runCatching { StageRuntime.refresh() }
            if (StageRuntime.hasHiddenWindows()) {
                runCatching { StageRuntime.ensureVisible("resume") }
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        super.onDestroy()
    }

    /** Shizuku needs an explicit permission grant before the UserService can be bound. */
    private suspend fun requestShizukuAndConnect() {
        if (!dev.stagelab.android.core.shizuku.ShizukuChannel.isServerRunning()) {
            StageRuntime.connect(BackendKind.SHIZUKU)
            return
        }
        if (dev.stagelab.android.core.shizuku.ShizukuChannel.hasPermission()) {
            StageRuntime.connect(BackendKind.SHIZUKU)
            return
        }
        runCatching {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        dev.stagelab.android.core.shizuku.ShizukuChannel.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    override fun onPause() {
        StageRuntime.suppressRecovery = true
        super.onPause()
    }

    @Suppress("unused")
    private fun openShizukuProbe() {
        startActivity(Intent(this, ShizukuProbeActivity::class.java))
    }

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 7101
    }
}

package dev.stagelab.android.stage

import android.content.Context
import android.content.Intent
import android.graphics.PointF
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.lifecycleScope
import dev.stagelab.android.core.stage.StageRect
import dev.stagelab.android.core.stage.StageRuntime
import dev.stagelab.android.ui.AppPickerActivity
import dev.stagelab.android.ui.StageLabTheme
import dev.stagelab.android.ui.rememberDockApps
import dev.stagelab.android.windows.WindowChromeController
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The ON state of the product: a full screen stage behind the real application windows.
 *
 * It renders the desk, the recent-group rail and the dock; the application windows themselves are
 * separate system tasks above this activity, decorated by [WindowChromeController] overlays.
 */
class StageHostActivity : ComponentActivity() {

    private lateinit var chrome: WindowChromeController

    @OptIn(FlowPreview::class)
    private val pendingGeometry = MutableStateFlow<Triple<String, StageRect, Boolean>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        StageModeController.attach(this)
        StageWorkspace.attach(this)

        chrome = WindowChromeController(this, chromeCallbacks)
        chrome.ensureEdgeStrip()
        current = this

        lifecycleScope.launch {
            pendingGeometry.collectLatest { pending ->
                if (pending == null) return@collectLatest
                val (id, rect, final) = pending
                StageWorkspace.updateGeometryPx(id, rect)
                StageWorkspace.commitGeometry(id)
                if (final) StageWorkspace.persist()
            }
        }

        setContent {
            StageLabTheme {
                val state by StageWorkspace.state.collectAsState()
                val density = LocalDensity.current
                val statusTop = WindowInsets.statusBars.getTop(density)
                val navBottom = WindowInsets.navigationBars.getBottom(density)
                val densityValue = remember { density.density }
                var dockApps by remember { mutableStateOf(emptyList<dev.stagelab.android.ui.DockApp>()) }
                val context = this

                LaunchedEffect(Unit) {
                    dockApps = dev.stagelab.android.ui.loadDockApps(context)
                }

                LaunchedEffect(state.windows.size) {
                    // The stage must never take focus while application windows are on screen: a
                    // focusable full screen window would be raised above them.
                    if (state.windows.any { it.alive }) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    }
                }

                LaunchedEffect(state.windows, state.railVisible) {
                    chrome.update(
                        windows = state.windows.map { window ->
                            WindowChromeController.ChromeWindow(
                                id = window.id,
                                rect = window.rect,
                                title = window.title,
                                focused = window.focused,
                                minimized = window.minimized
                            )
                        },
                        visible = state.ready
                    )
                }

                LaunchedEffect(state.railVisible, state.groups, state.addGroupRect, state.rail) {
                    val targets = ArrayList<WindowChromeController.TapTarget>()
                    state.groups.forEach { group ->
                        targets += WindowChromeController.TapTarget(group.rect) {
                            lifecycleScope.launch { StageWorkspace.activateGroup(group.id) }
                        }
                    }
                    if (state.railVisible && state.addGroupRect.width > 0) {
                        targets += WindowChromeController.TapTarget(state.addGroupRect) {
                            StageWorkspace.createGroup()
                            lifecycleScope.launch { StageWorkspace.syncGeometry("new-group") }
                        }
                    }
                    val bounds = if (state.railVisible && targets.isNotEmpty()) {
                        StageRect(
                            state.rail.left - 20,
                            state.rail.top - 20,
                            state.rail.right + 20,
                            (targets.maxOfOrNull { it.rect.bottom } ?: state.rail.bottom) + 20
                        )
                    } else {
                        null
                    }
                    chrome.updateRailTargets(targets, bounds, state.railVisible && targets.isNotEmpty())
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            StageWorkspace.setViewport(
                                width = size.width,
                                height = size.height,
                                densityValue = densityValue,
                                status = statusTop,
                                navigation = navBottom
                            )
                        }
                ) {
                    StageWorkspaceScreen(
                        state = state,
                        dockApps = dockApps,
                        onGroupClick = { id -> lifecycleScope.launch { StageWorkspace.activateGroup(id) } },
                        onNewGroup = {
                            StageWorkspace.createGroup()
                            lifecycleScope.launch { StageWorkspace.syncGeometry("new-group") }
                            StageWorkspace.setRailVisible(true)
                        },
                        onToggleRail = { StageWorkspace.toggleRail() },
                        onDockApp = { app -> lifecycleScope.launch { StageWorkspace.openApp(app.packageName, app.label, app.activityName) } },
                        onDockDrop = { app, point ->
                            lifecycleScope.launch { StageWorkspace.openApp(app.packageName, app.label, app.activityName, point) }
                        },
                        onAddApp = { openAppPicker() },
                        onExit = { lifecycleScope.launch { StageModeController.stop(); finish() } },
                        onRestoreMinimized = { id -> lifecycleScope.launch { StageWorkspace.restore(id) } },
                        onCloseMinimized = { id -> lifecycleScope.launch { StageWorkspace.closeWindow(id) } },
                        onEmptyTap = { StageWorkspace.setRailVisible(false) }
                    )
                }
            }
        }
    }

    private val chromeCallbacks = object : WindowChromeController.Callbacks {
        override fun onGeometryChanged(id: String, rect: StageRect, final: Boolean) {
            pendingGeometry.value = Triple(id, rect, final)
        }

        override fun onFocusRequested(id: String) {
            lifecycleScope.launch { StageWorkspace.focusWindow(id) }
        }

        override fun onCloseRequested(id: String) {
            lifecycleScope.launch { StageWorkspace.closeWindow(id) }
        }

        override fun onMinimizeRequested(id: String) {
            lifecycleScope.launch { StageWorkspace.minimize(id) }
        }

        override fun onLeftEdgeSwipe() {
            StageWorkspace.setRailVisible(true)
        }

        override fun onChromeTapOutside() = Unit
    }

    private fun openAppPicker() {
        lifecycleScope.launch {
            runCatching {
                val area = StageWorkspace.workspaceRect()
                val width = (area.width * 0.68f).roundToInt()
                val height = (area.height * 0.78f).roundToInt()
                val left = area.left + (area.width - width) / 2
                val top = area.top + (area.height - height) / 2
                StageRuntime.command(
                    JSONObject().put("cmd", "open")
                        .put("id", "picker-" + System.currentTimeMillis())
                        .put("package", packageName)
                        .put("activity", AppPickerActivity::class.java.name)
                        .put("title", "应用库")
                        .put("transient", true)
                        .put("l", left).put("t", top)
                        .put("r", left + width).put("b", top + height)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StageWorkspace.startPolling()
        lifecycleScope.launch {
            runCatching {
                // Tasks can be gone after a reboot / process death: relaunch the active group so
                // the workspace comes back as it was instead of showing empty frames.
                StageWorkspace.ensureGroupAlive(StageWorkspace.session.activeGroupId)
                StageWorkspace.syncGeometry("host-resume")
            }
        }
    }

    override fun onDestroy() {
        runCatching { chrome.destroy() }
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var current: StageHostActivity? = null

        fun requestFinish() {
            val activity = current ?: return
            runCatching { activity.finishAndRemoveTask() }
        }

        fun start(context: Context) {
            context.startActivity(
                Intent(context, StageHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

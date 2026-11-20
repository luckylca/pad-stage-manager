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

                LaunchedEffect(state.windows, state.railVisible, state.workspace) {
                    chrome.update(
                        windows = state.windows.map { window ->
                            WindowChromeController.ChromeWindow(
                                id = window.id,
                                rect = window.rect,
                                title = window.title,
                                focused = window.focused,
                                minimized = window.minimized,
                                deskTopColor = deskColorAt(window.rect.top, state),
                                deskBottomColor = deskColorAt(window.rect.bottom, state),
                                workspace = state.workspace
                            )
                        },
                        visible = state.ready
                    )
                }

                LaunchedEffect(state.railVisible, state.groups, state.addGroupRect, state.rail, state.railUpRect, state.railDownRect) {
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
                    if (state.railUpRect.width > 0) {
                        targets += WindowChromeController.TapTarget(state.railUpRect) {
                            StageWorkspace.scrollRail(360f)
                        }
                    }
                    if (state.railDownRect.width > 0) {
                        targets += WindowChromeController.TapTarget(state.railDownRect) {
                            StageWorkspace.scrollRail(-360f)
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
                    chrome.updateRailTargets(targets, bounds, state.railVisible && targets.isNotEmpty()) { dy ->
                        StageWorkspace.scrollRail(dy)
                    }
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
                        onRailScroll = { dir ->
                            StageWorkspace.scrollRail(-dir * resources.displayMetrics.density * 140f)
                        },
                        onToggleRail = { StageWorkspace.toggleRail() },
                        onDockApp = { app -> lifecycleScope.launch { StageWorkspace.openApp(app.packageName, app.label, app.activityName) } },
                        onDockDrop = { app, point ->
                            lifecycleScope.launch { StageWorkspace.openApp(app.packageName, app.label, app.activityName, point) }
                        },
                        onAddApp = { openAppPicker() },
                        onExit = { lifecycleScope.launch { StageModeController.stop(); finish() } },
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

        override fun onZoomToggled(id: String, maximized: Boolean) {
            // Geometry is committed through onGeometryChanged; nothing else to do for now.
        }

        override fun onLeftEdgeSwipe() {
            StageWorkspace.setRailVisible(true)
        }

        override fun onChromeTapOutside() = Unit
    }

    /** Desk colour at a given y so the corner masks match the background exactly. */
    private fun deskColorAt(y: Int, state: dev.stagelab.android.stage.WorkspaceState): Int {
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val t = (y.toFloat() / height).coerceIn(0f, 1f)
        return lerpColor(0xFF141922.toInt(), 0xFF0A0D13.toInt(), t)
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val a = ((from ushr 24 and 0xFF) + ((to ushr 24 and 0xFF) - (from ushr 24 and 0xFF)) * t).toInt()
        val r = ((from ushr 16 and 0xFF) + ((to ushr 16 and 0xFF) - (from ushr 16 and 0xFF)) * t).toInt()
        val g = ((from ushr 8 and 0xFF) + ((to ushr 8 and 0xFF) - (from ushr 8 and 0xFF)) * t).toInt()
        val b = ((from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * t).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
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

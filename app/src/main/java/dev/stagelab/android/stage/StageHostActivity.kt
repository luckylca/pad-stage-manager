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
import dev.stagelab.android.ui.WallpaperBackdrop
import dev.stagelab.android.ui.rememberDockApps
import dev.stagelab.android.windows.StageTransitionOverlay
import dev.stagelab.android.windows.WindowChromeController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The ON state of the product: a full screen stage behind the real application windows.
 *
 * It renders the desk, the recent-group rail and the dock; the application windows themselves are
 * separate system tasks above this activity, decorated by [WindowChromeController] overlays.
 */
class StageHostActivity : ComponentActivity() {

    private data class GeometryCommit(
        val rect: StageRect,
        val final: Boolean,
        val resized: Boolean
    )

    private lateinit var chrome: WindowChromeController
    private lateinit var exposeOverlay: dev.stagelab.android.windows.StageExposeOverlay
    private lateinit var transitionOverlay: StageTransitionOverlay
    private lateinit var wallpaperBackdrop: WallpaperBackdrop

    /** Per-window conflated channels for live drag (zero Compose recomposition) and final commits. */
    private val liveMoveQueues = ConcurrentHashMap<String, Channel<StageRect>>()
    private val geometryQueues = ConcurrentHashMap<String, Channel<GeometryCommit>>()

    private fun enqueueLiveMove(id: String, rect: StageRect) {
        val channel = liveMoveQueues.computeIfAbsent(id) {
            val ch = Channel<StageRect>(Channel.CONFLATED)
            lifecycleScope.launch {
                for (r in ch) {
                    StageWorkspace.moveWindowLive(id, r)
                }
            }
            ch
        }
        channel.trySend(rect)
    }

    private fun enqueueGeometry(id: String, rect: StageRect, final: Boolean, resized: Boolean) {
        val channel = geometryQueues.computeIfAbsent(id) {
            val ch = Channel<GeometryCommit>(Channel.CONFLATED)
            lifecycleScope.launch {
                for (update in ch) {
                    StageWorkspace.updateGeometryPx(id, update.rect)
                    StageWorkspace.commitGeometry(id)
                    if (update.final) {
                        if (update.resized) StageWorkspace.rememberWindowSize(id)
                        StageWorkspace.persist()
                    }
                }
            }
            ch
        }
        channel.trySend(GeometryCommit(rect, final, resized))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        StageModeController.attach(this)
        StageWorkspace.attach(this)

        chrome = WindowChromeController(this, chromeCallbacks)
        exposeOverlay = dev.stagelab.android.windows.StageExposeOverlay(this)
        transitionOverlay = StageTransitionOverlay(this).also { it.attach() }
        wallpaperBackdrop = WallpaperBackdrop.load(this)
        StageWorkspace.transitionPlayer = { cards, durationMs ->
            transitionOverlay.play(cards, durationMs)
        }
        chrome.ensureEdgeStrip()
        current = this

        setContent {
            StageLabTheme {
                val state by StageWorkspace.state.collectAsState()
                val density = LocalDensity.current
                val statusTop = WindowInsets.statusBars.getTop(density)
                val navBottom = WindowInsets.navigationBars.getBottom(density)
                val densityValue = remember { density.density }
                var dockModel by remember { mutableStateOf(dev.stagelab.android.ui.DockModel()) }
                val context = this

                LaunchedEffect(state.dockRefreshVersion) {
                    dockModel = dev.stagelab.android.ui.loadDockModel(context)
                }

                LaunchedEffect(state.ready, state.landscape, navBottom, statusTop) {
                    chrome.updateBottomEdgeStrip(state.ready, navBottom)
                    chrome.updateMenuBarPill(state.ready, statusTop)
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

                LaunchedEffect(state.windows, state.railVisible, state.workspace, state.fullScreenBounds) {
                    chrome.update(
                        windows = state.windows.map { window ->
                            WindowChromeController.ChromeWindow(
                                id = window.id,
                                rect = window.rect,
                                title = window.title,
                                focused = window.focused,
                                minimized = window.minimized,
                                workspace = state.workspace,
                                fullScreenBounds = state.fullScreenBounds
                            )
                        },
                        visible = state.ready
                    )
                }

                LaunchedEffect(state.dropHighlight) {
                    val hl = state.dropHighlight
                    if (hl.kind == DropZoneKind.NONE) {
                        chrome.updateDropHighlight(null, "")
                    } else {
                        chrome.updateDropHighlight(hl.rect, hl.label)
                    }
                }

                LaunchedEffect(state.railVisible, state.groups, state.addGroupRect, state.rail, state.railUpRect, state.railDownRect) {
                    val targets = ArrayList<WindowChromeController.TapTarget>()
                    state.groups.forEach { group ->
                        targets += WindowChromeController.TapTarget(
                            rect = group.rect,
                            groupId = group.id
                        ) {
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
                    chrome.updateRailTargets(
                        targets = targets,
                        bounds = bounds,
                        visible = state.railVisible && targets.isNotEmpty(),
                        onScroll = { dy -> StageWorkspace.scrollRail(dy) },
                        onRailDragMove = { groupId, x, y ->
                            StageWorkspace.updateExternalDragTarget(x, y, sourceGroupId = groupId)
                        },
                        onRailDragEnd = { groupId, x, y ->
                            StageWorkspace.clearDropHighlight()
                            val ws = StageWorkspace.workspaceRect()
                            if (x >= ws.left && x <= ws.right && y >= ws.top && y <= ws.bottom) {
                                lifecycleScope.launch {
                                    StageWorkspace.addGroupToActiveGroup(groupId, PointF(x.toFloat(), y.toFloat()))
                                }
                            }
                        }
                    )
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
                        dockModel = dockModel,
                        wallpaper = wallpaperBackdrop,
                        onGroupClick = { id -> lifecycleScope.launch { StageWorkspace.activateGroup(id) } },
                        onNewGroup = {
                            StageWorkspace.createGroup()
                            lifecycleScope.launch { StageWorkspace.syncGeometry("new-group") }
                            StageWorkspace.setRailVisible(true)
                        },
                        onRailScroll = { dir ->
                            StageWorkspace.scrollRail(-dir * resources.displayMetrics.density * 140f)
                        },
                        onDockApp = { app ->
                            lifecycleScope.launch {
                                StageWorkspace.activateOrOpenApp(app.packageName, app.label, app.activityName)
                            }
                        },
                        onDockLongPress = { app, pos ->
                            showDockContextMenu(app, pos.x.roundToInt(), pos.y.roundToInt(), dockModel)
                        },
                        onDockDismissMenu = { chrome.dismissWindowMenu() },
                        onDockDragMove = { point ->
                            StageWorkspace.updateExternalDragTarget(point.x.roundToInt(), point.y.roundToInt())
                        },
                        onDockDrop = { app, point ->
                            StageWorkspace.clearDropHighlight()
                            val currentState = StageWorkspace.state.value
                            val x = point.x.roundToInt()
                            val y = point.y.roundToInt()
                            val ws = currentState.workspace
                            val railTile = if (currentState.railVisible) {
                                currentState.groups.firstOrNull { g ->
                                    x >= g.rect.left && x <= g.rect.right && y >= g.rect.top && y <= g.rect.bottom
                                }
                            } else null
                            when {
                                x >= ws.left && x <= ws.right && y >= ws.top && y <= ws.bottom -> {
                                    lifecycleScope.launch {
                                        StageWorkspace.openApp(app.packageName, app.label, app.activityName, point)
                                    }
                                }
                                railTile != null -> {
                                    lifecycleScope.launch {
                                        StageWorkspace.openAppIntoGroup(railTile.id, app.packageName, app.label, app.activityName)
                                    }
                                }
                            }
                        },
                        onDockDragCancel = { StageWorkspace.clearDropHighlight() },
                        onAddApp = { openAppPicker() },
                        onEmptyTap = {
                            if (state.dockAutoHide) StageWorkspace.setDockRevealed(false)
                            if (!state.landscape) StageWorkspace.setRailVisible(false)
                        }
                    )
                }
            }
        }
    }

    private val chromeCallbacks = object : WindowChromeController.Callbacks {
        override fun onWindowMovedLive(id: String, rect: StageRect) {
            enqueueLiveMove(id, rect)
        }

        override fun onGeometryChanged(id: String, rect: StageRect, final: Boolean, resized: Boolean) {
            enqueueGeometry(id, rect, final, resized)
        }

        override fun onFullScreenToggleRequested(id: String) {
            lifecycleScope.launch { StageWorkspace.toggleFullScreen(id) }
        }

        override fun onGestureStateChanged(id: String, active: Boolean) {
            StageWorkspace.setWindowGestureActive(id, active)
        }

        override fun onWindowDragCancelled(id: String) {
            StageWorkspace.clearDropHighlight()
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

        override fun onZoomToggled(id: String, maximized: Boolean) = Unit

        override fun onWindowMenuRequested(id: String, anchorX: Int, anchorY: Int) {
            val window = StageWorkspace.session.windows[id] ?: return
            val isFull = StageWorkspace.isFullScreen(id)
            val items = ArrayList<WindowChromeController.MenuItem>()
            items += WindowChromeController.MenuItem(if (isFull) "退出全屏幕" else "全屏幕") {
                lifecycleScope.launch { StageWorkspace.toggleFullScreen(id) }
            }
            items += WindowChromeController.MenuItem("添加另一个窗口") {
                openAppPicker()
            }
            if (dev.stagelab.android.ui.supportsMultiInstance(this@StageHostActivity, window.packageName, window.component)) {
                items += WindowChromeController.MenuItem("新建窗口") {
                    lifecycleScope.launch {
                        StageWorkspace.openNewWindow(window.packageName, window.label, window.component)
                    }
                }
            }
            items += WindowChromeController.MenuItem("贴靠左侧") {
                lifecycleScope.launch { StageWorkspace.arrangeWindow(id, WindowArrangePreset.LEFT_HALF) }
            }
            items += WindowChromeController.MenuItem("贴靠右侧") {
                lifecycleScope.launch { StageWorkspace.arrangeWindow(id, WindowArrangePreset.RIGHT_HALF) }
            }
            items += WindowChromeController.MenuItem("最小化") {
                lifecycleScope.launch { StageWorkspace.minimize(id) }
            }
            val sameAppWindows = StageWorkspace.windowsForPackage(window.packageName)
            if (sameAppWindows.size > 1) {
                items += WindowChromeController.MenuItem("显示所有窗口 (${sameAppWindows.size})") {
                    showAppExpose(window.packageName, window.label, window.component)
                }
            }
            items += WindowChromeController.MenuItem("关闭", danger = true) {
                lifecycleScope.launch { StageWorkspace.closeWindow(id) }
            }
            chrome.showWindowMenu(window.label, anchorX, anchorY, items)
        }

        override fun onWindowDragging(id: String, x: Int, y: Int) {
            StageWorkspace.updateWindowDragTarget(x, y)
        }

        override fun onWindowDropped(id: String, x: Int, y: Int) {
            StageWorkspace.clearDropHighlight()
            lifecycleScope.launch {
                runCatching { StageWorkspace.handleWindowDrop(id, x, y) }
            }
        }

        override fun onLeftEdgeSwipe() {
            StageWorkspace.setRailVisible(true)
        }

        override fun onBottomEdgeSwipeUp() {
            StageWorkspace.setDockRevealed(true)
        }

        override fun onBottomSwipeUpHold() {
            showStageOverview()
        }

        override fun onBottomHorizontalSwipe(direction: Int) {
            lifecycleScope.launch { StageWorkspace.switchAdjacentGroup(direction) }
        }

        override fun onGlobalMenuBarClick(anchorX: Int, anchorY: Int) {
            showGlobalWindowMenu(anchorX, anchorY)
        }

        override fun onChromeTapOutside() = Unit
    }

    /** Hardware keyboard shortcuts for external keyboards (P9). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val mod = event.isMetaPressed || event.isAltPressed
            if (mod) {
                val focusedId = StageWorkspace.state.value.focusedWindow?.id
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_TAB -> {
                        lifecycleScope.launch {
                            StageWorkspace.switchAdjacentGroup(if (event.isShiftPressed) -1 else 1)
                        }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        showStageOverview()
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (focusedId != null) {
                        lifecycleScope.launch { StageWorkspace.arrangeWindow(focusedId, WindowArrangePreset.LEFT_HALF) }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (focusedId != null) {
                        lifecycleScope.launch { StageWorkspace.arrangeWindow(focusedId, WindowArrangePreset.RIGHT_HALF) }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_M -> if (focusedId != null) {
                        lifecycleScope.launch { StageWorkspace.minimize(focusedId) }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_W -> if (focusedId != null) {
                        lifecycleScope.launch { StageWorkspace.closeWindow(focusedId) }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_N -> {
                        StageWorkspace.createGroup()
                        lifecycleScope.launch { StageWorkspace.syncGeometry("shortcut-new-group") }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** StageLab's iPad-style global Window menu (P9). */
    private fun showGlobalWindowMenu(anchorX: Int, anchorY: Int) {
        val state = StageWorkspace.state.value
        val focused = state.focusedWindow
        val items = ArrayList<WindowChromeController.MenuItem>()
        items += WindowChromeController.MenuItem("双窗左右并排") {
            lifecycleScope.launch { StageWorkspace.arrangeActiveGroup(GroupArrangePreset.SIDE_BY_SIDE) }
        }
        items += WindowChromeController.MenuItem("层叠排列窗口") {
            lifecycleScope.launch { StageWorkspace.arrangeActiveGroup(GroupArrangePreset.CASCADE) }
        }
        if (focused != null) {
            items += WindowChromeController.MenuItem("最大化 / 还原「${focused.title}」") {
                chrome.toggleZoom(focused.id)
            }
            items += WindowChromeController.MenuItem("最小化「${focused.title}」") {
                lifecycleScope.launch { StageWorkspace.minimize(focused.id) }
            }
        }
        items += WindowChromeController.MenuItem("显示所有工作台") {
            showStageOverview()
        }
        val displayManager = getSystemService(android.hardware.display.DisplayManager::class.java)
        val externalDisplays = displayManager?.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?: emptyArray()
        val displaySummary = if (externalDisplays.isEmpty()) {
            "外接显示器：未连接"
        } else {
            val d = externalDisplays.first()
            "外接显示器：${d.name} (#${d.displayId})"
        }
        items += WindowChromeController.MenuItem(displaySummary) { /* informational probe */ }
        chrome.showWindowMenu("窗口 · ${state.activeGroupTitle}", anchorX, anchorY, items)
    }

    /** iPad-style Dock icon context menu (P5). */
    private fun showDockContextMenu(
        app: dev.stagelab.android.ui.DockApp,
        anchorX: Int,
        anchorY: Int,
        dockModel: dev.stagelab.android.ui.DockModel
    ) {
        val items = ArrayList<WindowChromeController.MenuItem>()
        items += WindowChromeController.MenuItem("加入当前工作台") {
            lifecycleScope.launch {
                StageWorkspace.openApp(app.packageName, app.label, app.activityName, null)
            }
        }
        if (app.supportsMultiWindow) {
            items += WindowChromeController.MenuItem("新建窗口 (New Window)") {
                lifecycleScope.launch {
                    StageWorkspace.openNewWindow(app.packageName, app.label, app.activityName)
                }
            }
        }
        val appWindows = StageWorkspace.windowsForPackage(app.packageName)
        if (appWindows.isNotEmpty()) {
            items += WindowChromeController.MenuItem("显示所有窗口 (${appWindows.size})") {
                showAppExpose(app.packageName, app.label, app.activityName)
            }
        }
        val currentPinned = dockModel.pinned.map { it.packageName }
        if (app.pinned) {
            items += WindowChromeController.MenuItem("从 Dock 移除") {
                dev.stagelab.android.ui.DockConfigStore.unpin(this, app.packageName, currentPinned)
                StageWorkspace.notifyDockChanged()
            }
        } else {
            items += WindowChromeController.MenuItem("固定到 Dock") {
                dev.stagelab.android.ui.DockConfigStore.pin(this, app.packageName, currentPinned)
                StageWorkspace.notifyDockChanged()
            }
        }
        val autoHide = StageWorkspace.state.value.dockAutoHide
        items += WindowChromeController.MenuItem(if (autoHide) "始终显示 Dock" else "自动隐藏 Dock") {
            StageWorkspace.setDockAutoHide(!autoHide)
        }
        val railAuto = StageWorkspace.state.value.railAutoHide
        items += WindowChromeController.MenuItem(if (railAuto) "始终显示最近工作台" else "自动隐藏最近工作台") {
            StageWorkspace.setRailAutoHide(!railAuto)
        }
        val menuY = (anchorY - (items.size * 115 + 120)).coerceAtLeast(80)
        chrome.showWindowMenu(app.label, anchorX - 180, menuY, items)
    }

    /** Shows iPad-style App Exposé for [packageName] above all real tasks (P6). */
    private fun showAppExpose(packageName: String, appLabel: String, activityName: String) {
        val activeId = StageWorkspace.session.activeGroupId
        val focusedId = StageWorkspace.state.value.focusedWindow?.id
        val appWindows = StageWorkspace.windowsForPackage(packageName)
        val canNew = dev.stagelab.android.ui.supportsMultiInstance(this, packageName, activityName)
        val cards = appWindows.map { w ->
            val owner = StageWorkspace.session.groupOf(w.id)
            val inActive = owner?.id == activeId
            dev.stagelab.android.windows.StageExposeOverlay.WindowCard(
                windowId = w.id,
                taskId = w.taskId,
                title = w.label,
                groupTitle = owner?.title ?: "工作区",
                inActiveGroup = inActive,
                focused = w.id == focusedId,
                onSelect = {
                    lifecycleScope.launch {
                        if (owner != null && owner.id != StageWorkspace.session.activeGroupId) {
                            StageWorkspace.activateGroup(owner.id)
                        }
                        StageWorkspace.focusWindow(w.id)
                    }
                },
                onBringToCurrentGroup = if (!inActive) {
                    { lifecycleScope.launch { StageWorkspace.bringWindowToActiveGroup(w.id) } }
                } else null,
                onClose = {
                    lifecycleScope.launch { StageWorkspace.closeWindow(w.id) }
                }
            )
        }
        exposeOverlay.showAppExpose(
            appLabel = appLabel,
            windows = cards,
            canCreateNewWindow = canNew,
            onNewWindow = {
                lifecycleScope.launch {
                    StageWorkspace.openNewWindow(packageName, appLabel, activityName)
                }
            }
        )
    }

    /** Shows Stage Overview with all workspaces (P6/P7). */
    private fun showStageOverview() {
        val activeId = StageWorkspace.session.activeGroupId
        val cards = StageWorkspace.session.sortedGroups().map { g ->
            val names = StageWorkspace.session.visibleWindowsOf(g.id).map { "${it.label} (#${it.taskId})" }
            dev.stagelab.android.windows.StageExposeOverlay.GroupCard(
                groupId = g.id,
                title = g.title,
                active = g.id == activeId,
                windowSummary = names,
                onSelect = {
                    lifecycleScope.launch { StageWorkspace.activateGroup(g.id) }
                }
            )
        }
        exposeOverlay.showStageOverview(
            groups = cards,
            onNewGroup = {
                StageWorkspace.createGroup()
                lifecycleScope.launch { StageWorkspace.syncGeometry("overview-new-group") }
            }
        )
    }

    /** Samples the wallpaper/desk colour at (x, y) so window corner masks blend cleanly. */
    private fun deskColorAt(x: Int, y: Int): Int {
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        return wallpaperBackdrop.sampleColor(x.toFloat() / width, y.toFloat() / height)
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
        StageWorkspace.transitionPlayer = null
        liveMoveQueues.values.forEach { it.close() }
        liveMoveQueues.clear()
        geometryQueues.values.forEach { it.close() }
        geometryQueues.clear()
        runCatching { transitionOverlay.destroy() }
        runCatching { exposeOverlay.dismiss() }
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

        fun openAppExpose(packageName: String) {
            val activity = current ?: return
            activity.runOnUiThread {
                val first = StageWorkspace.windowsForPackage(packageName).firstOrNull()
                activity.showAppExpose(
                    packageName = packageName,
                    appLabel = first?.label ?: packageName,
                    activityName = first?.component.orEmpty()
                )
            }
        }

        fun openOverview() {
            val activity = current ?: return
            activity.runOnUiThread { activity.showStageOverview() }
        }

        fun start(context: Context) {
            context.startActivity(
                Intent(context, StageHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

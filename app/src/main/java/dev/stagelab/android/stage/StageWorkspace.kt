package dev.stagelab.android.stage

import android.content.Context
import android.util.Log
import dev.stagelab.android.core.ipc.ChannelException
import dev.stagelab.android.core.stage.StageRect
import dev.stagelab.android.core.stage.StageRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class GroupWindowPreview(
    val id: String,
    val taskId: Int,
    val packageName: String,
    val label: String
)

data class GroupTile(
    val id: String,
    val title: String,
    val windowCount: Int,
    val active: Boolean,
    val rect: StageRect,
    val live: Boolean,
    val previews: List<GroupWindowPreview> = emptyList()
)

data class WindowTile(
    val id: String,
    val taskId: Int,
    val title: String,
    val packageName: String,
    val rect: StageRect,
    val focused: Boolean,
    val minimized: Boolean,
    val alive: Boolean
)

enum class WindowArrangePreset {
    LEFT_HALF,
    RIGHT_HALF,
    CENTER,
    FULL
}

enum class GroupArrangePreset {
    SIDE_BY_SIDE,
    CASCADE
}

enum class DropZoneKind {
    NONE,
    WORKSPACE,
    RAIL_REMOVE,
    GROUP_TILE,
    ADD_GROUP
}

data class DropHighlight(
    val kind: DropZoneKind = DropZoneKind.NONE,
    val targetGroupId: String? = null,
    val rect: StageRect = StageRect(0, 0, 0, 0),
    val label: String = ""
)

data class WorkspaceState(
    val ready: Boolean = false,
    val activeGroupId: String = "",
    val activeGroupTitle: String = "",
    val groups: List<GroupTile> = emptyList(),
    val windows: List<WindowTile> = emptyList(),
    val minimized: List<WindowTile> = emptyList(),
    val railVisible: Boolean = false,
    val workspace: StageRect = StageRect(0, 0, 0, 0),
    val dock: StageRect = StageRect(0, 0, 0, 0),
    val addGroupRect: StageRect = StageRect(0, 0, 0, 0),
    val rail: StageRect = StageRect(0, 0, 0, 0),
    val landscape: Boolean = false,
    /** Chevron targets shown when the rail has more groups than fit. */
    val railUpRect: StageRect = StageRect(0, 0, 0, 0),
    val railDownRect: StageRect = StageRect(0, 0, 0, 0),
    val dropHighlight: DropHighlight = DropHighlight(),
    val dockVisible: Boolean = true,
    val dockAutoHide: Boolean = false,
    val railAutoHide: Boolean = false,
    val fullScreenWindowId: String? = null,
    val fullScreenBounds: StageRect = StageRect(0, 0, 0, 0),
    val activePackages: Set<String> = emptySet(),
    val runningPackages: Set<String> = emptySet(),
    val dockRefreshVersion: Int = 0,
    val backend: String = "",
    val error: String? = null
) {
    val focusedWindow: WindowTile? get() = windows.firstOrNull { it.focused } ?: windows.firstOrNull()
}

/**
 * Owns the Stage Manager workspace: the session model, the geometry of every window, and the
 * translation of that geometry into real Android task bounds.
 *
 * The host activity is only a renderer/gesture surface; every mutation goes through here so that
 * the same operations can be driven by the UI, by drag gestures, or by the test receiver.
 */
object StageWorkspace {

    private const val TAG = "StageWorkspace"
    const val MAX_WINDOWS_PER_STAGE = 4
    const val MAX_RECENT_STAGES = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var store: StageSessionStore? = null
    private var pollJob: Job? = null
    private var applying = false
    private val activeGestureWindows = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var lastSnapshotPollMs = 0L

    /** Registered by [StageHostActivity] to play 60/120fps 3D spring transitions over real tasks. */
    var transitionPlayer: (suspend (List<dev.stagelab.android.windows.StageTransitionOverlay.TransitionCard>, Long) -> Unit)? = null

    fun setWindowGestureActive(windowId: String, active: Boolean) {
        if (active) activeGestureWindows.add(windowId) else activeGestureWindows.remove(windowId)
    }

    fun rememberWindowSize(windowId: String) {
        val w = session.windows[windowId] ?: return
        WindowSizing.rememberSize(appContext, w.packageName, w.geometry.w, w.geometry.h)
    }

    var session: StageSession = StageSession.createDefault()
        private set

    // viewport (px)
    private var screenW = 0
    private var screenH = 0
    private var density = 2.625f
    private var statusInset = 0
    private var navInset = 0

    /**
     * Rail visibility is orientation dependent:
     *  * landscape (the primary mode) – the recent-group rail is a persistent left column;
     *  * portrait – it is a drawer that opens from the left edge.
     */
    private var landscape = false
    private var railHiddenByUser = false
    private var railPinnedPortrait = false
    private var railScroll = 0f
    private var currentDropHighlight = DropHighlight()
    private var dockAutoHide = false
    private var dockRevealed = false
    private var railAutoHide = false
    private var railTempRevealed = false
    private var fullScreenWindowId: String? = null
    private var fullScreenSavedGeometry: WindowGeometry? = null
    private var dockRefreshVersion = 0

    fun isLandscape(): Boolean = landscape

    fun isFullScreen(windowId: String? = null): Boolean =
        if (windowId == null) fullScreenWindowId != null else fullScreenWindowId == windowId

    private fun railShouldShow(): Boolean = when {
        fullScreenWindowId != null -> false
        landscape && railAutoHide -> railTempRevealed
        landscape -> !railHiddenByUser
        else -> railPinnedPortrait
    }

    private fun dockShouldShow(): Boolean = when {
        fullScreenWindowId != null -> false
        else -> !dockAutoHide || dockRevealed
    }

    fun fullScreenRect(): StageRect =
        if (screenW > 0 && screenH > 0) {
            StageRect(0, statusInset, screenW, screenH - navInset)
        } else StageRect(0, 0, 0, 0)

    fun setRailAutoHide(enabled: Boolean) {
        if (railAutoHide == enabled) return
        railAutoHide = enabled
        railTempRevealed = false
        appContext?.getSharedPreferences("stagelab_prefs_v1", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("rail_auto_hide", enabled)?.apply()
        computeLayout()
        publishState()
        scope.launch { runCatching { syncGeometry("rail-autohide") } }
    }

    fun setDockAutoHide(enabled: Boolean) {
        if (dockAutoHide == enabled) return
        dockAutoHide = enabled
        dockRevealed = false
        appContext?.getSharedPreferences("stagelab_prefs_v1", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("dock_auto_hide", enabled)?.apply()
        computeLayout()
        publishState()
        scope.launch { runCatching { syncGeometry("dock-autohide") } }
    }

    fun setDockRevealed(revealed: Boolean) {
        if (!dockAutoHide) return
        if (dockRevealed == revealed) return
        dockRevealed = revealed
        computeLayout()
        publishState()
    }

    fun notifyDockChanged() {
        dockRefreshVersion++
        publishState()
    }

    // ------------------------------------------------------------------ layout

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun computeLayout() {
        if (screenW <= 0 || screenH <= 0) return
        landscape = screenW > screenH
        val dockW = min(screenW - dp(48f), dp(760f))
        val dockH = dp(84f)
        val dock = StageRect(
            left = (screenW - dockW) / 2,
            top = screenH - navInset - dp(14f) - dockH,
            right = (screenW + dockW) / 2,
            bottom = screenH - navInset - dp(14f)
        )
        val dockShown = dockShouldShow()
        val dockBottomEdge = if (dockShown) dock.top - dp(12f) else screenH - navInset - dp(12f)
        val railLeft = dp(12f)
        val rail = StageRect(
            left = railLeft,
            top = statusInset + dp(10f),
            right = railLeft + railWidthPx(),
            bottom = dockBottomEdge
        )
        val workspace = if (fullScreenWindowId != null) {
            fullScreenRect()
        } else {
            val workspaceLeft = if (railShouldShow()) rail.right + dp(14f) else dp(18f)
            StageRect(
                left = workspaceLeft,
                top = statusInset + dp(10f),
                right = screenW - dp(18f),
                bottom = dockBottomEdge
            )
        }
        _layout = Layout(dock = dock, workspace = workspace, dockHeight = dockH, rail = rail)
    }

    private data class Layout(
        val dock: StageRect,
        val workspace: StageRect,
        val dockHeight: Int,
        val rail: StageRect
    )

    private var _layout: Layout? = null
    private val layout: Layout? get() = _layout

    fun setViewport(width: Int, height: Int, densityValue: Float, status: Int, navigation: Int) {
        val wasLandscape = landscape
        val changed = width != screenW || height != screenH || densityValue != density
        screenW = width
        screenH = height
        density = densityValue
        statusInset = status
        navInset = navigation
        computeLayout()
        if (wasLandscape != landscape) {
            // Orientation change: the rail mode follows the new posture.
            railPinnedPortrait = false
            railScroll = 0f
        }
        publishState()
        if (changed) {
            scope.launch { runCatching { syncGeometry("viewport") } }
        }
    }

    fun railWidthPx(): Int = dp(140f)

    fun railRect(): StageRect? = layout?.rail

    fun workspaceRect(): StageRect = layout?.workspace ?: StageRect(0, 0, 0, 0)

    fun dockRect(): StageRect = layout?.dock ?: StageRect(0, 0, 0, 0)

    private fun railItemHeightPx(): Int = dp(94f)
    private fun railItemGapPx(): Int = dp(14f)

    /** Vertical scroll of the rail, in pixels. */
    fun scrollRail(delta: Float) {
        val rail = railRect() ?: return
        val items = (session.sortedGroups().size - 1).coerceAtLeast(0)
        if (items <= 0) return
        val perItem = railItemHeightPx() + railItemGapPx()
        val totalHeight = items * perItem - railItemGapPx()
        val maxScroll = (totalHeight - rail.height).coerceAtLeast(0).toFloat()
        val next = (railScroll - delta).coerceIn(0f, maxScroll)
        if (next == railScroll) return
        railScroll = next
        publishState()
    }

    /**
     * Authentic iPad Stage Manager left rail slots (G1-G5):
     * narrow (~140dp) vertically centered column of rounded 3D-tilted snapshot thumbnails
     * floating directly over the wallpaper — no dark container, no text, no arrows, no "+" button.
     */
    fun tileRect(index: Int, count: Int): StageRect? {
        val rail = railRect() ?: return null
        if (count <= 0) return null
        val itemH = railItemHeightPx()
        val gap = railItemGapPx()
        val totalH = count * itemH + (count - 1).coerceAtLeast(0) * gap
        val startTop = if (totalH < rail.height) {
            rail.top + (rail.height - totalH) / 2
        } else {
            rail.top + dp(6f)
        }
        val top = startTop + index * (itemH + gap) - railScroll.roundToInt()
        val bottom = top + itemH
        if (bottom < rail.top || top > rail.bottom) return null
        return StageRect(rail.left, top, rail.right, bottom)
    }

    fun isLiveRailSlot(index: Int): Boolean = false

    fun railCanScroll(): Boolean {
        val rail = railRect() ?: return false
        val items = (session.sortedGroups().size - 1).coerceAtLeast(0)
        val totalH = items * railItemHeightPx() + (items - 1).coerceAtLeast(0) * railItemGapPx()
        return totalH > rail.height + dp(4f)
    }

    /** The live preview is inset so the tile ring and its caption strip stay visible. */
    private fun tileInner(rect: StageRect): StageRect = StageRect(
        rect.left + dp(6f),
        rect.top + dp(6f),
        rect.right - dp(6f),
        rect.bottom - dp(30f)
    )

    // ------------------------------------------------------------------ lifecycle

    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        store = StageSessionStore(context.applicationContext)
        session = store?.load() ?: StageSession.createDefault()
        dockAutoHide = context.applicationContext
            .getSharedPreferences("stagelab_prefs_v1", Context.MODE_PRIVATE)
            .getBoolean("dock_auto_hide", false)
        railAutoHide = context.applicationContext
            .getSharedPreferences("stagelab_prefs_v1", Context.MODE_PRIVATE)
            .getBoolean("rail_auto_hide", false)
        publishState()
    }

    fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { reconcileTasks() }
                delay(1000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun shutdown() {
        stopPolling()
        persist()
    }

    fun reloadSession() {
        session = store?.load() ?: StageSession.createDefault()
        publishState()
    }

    /** Closes every staged task and returns the workspace to a single empty group. */
    suspend fun resetSession() {
        session.windows.values.filter { it.alive }.forEach { window ->
            runCatching { StageRuntime.command(JSONObject().put("cmd", "close").put("taskId", window.taskId)) }
        }
        session = StageSession.createDefault()
        persist()
        publishState()
    }

    fun persist() {
        runCatching { store?.save(session) }
    }

    // ------------------------------------------------------------------ geometry

    private fun pxRectFor(geometry: WindowGeometry): StageRect {
        val area = workspaceRect()
        return StageRect(
            left = area.left + (area.width * geometry.x).roundToInt(),
            top = area.top + (area.height * geometry.y).roundToInt(),
            right = area.left + (area.width * (geometry.x + geometry.w)).roundToInt(),
            bottom = area.top + (area.height * (geometry.y + geometry.h)).roundToInt()
        )
    }

    private fun geometryFor(rect: StageRect): WindowGeometry {
        val area = workspaceRect()
        if (area.width <= 0 || area.height <= 0) return WindowGeometry(0.1f, 0.1f, 0.6f, 0.6f)
        val geometry = WindowGeometry(
            x = (rect.left - area.left).toFloat() / area.width,
            y = (rect.top - area.top).toFloat() / area.height,
            w = rect.width.toFloat() / area.width,
            h = rect.height.toFloat() / area.height
        )
        geometry.clamp()
        return geometry
    }

    fun windowRect(windowId: String): StageRect? {
        val window = session.windows[windowId] ?: return null
        return pxRectFor(window.geometry)
    }

    /** Zero-recomposition live translation during finger drag (60-120fps). */
    suspend fun moveWindowLive(windowId: String, rect: StageRect) {
        val window = session.windows[windowId] ?: return
        if (!window.alive) return
        runCatching { applyBounds(window.taskId, rect) }
    }

    /** Toggles Full-Screen Retreat mode: hides Recent Strip & Dock and fills the screen; exiting restores both. */
    suspend fun toggleFullScreen(windowId: String) {
        val window = session.windows[windowId] ?: return
        if (fullScreenWindowId == windowId) {
            exitFullScreen()
            return
        }
        if (fullScreenWindowId != null) {
            exitFullScreen(syncNow = false)
        }
        fullScreenSavedGeometry = window.geometry.copyOf()
        fullScreenWindowId = windowId
        computeLayout()
        window.geometry = WindowGeometry(0f, 0f, 1f, 1f)
        session.bringToFront(windowId)
        persist()
        publishState()
        val full = fullScreenRect()
        if (window.alive && full.width > 0) {
            runCatching { applyBounds(window.taskId, full) }
            runCatching { StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", window.taskId)) }
        }
    }

    suspend fun exitFullScreen(syncNow: Boolean = true) {
        val id = fullScreenWindowId ?: return
        val saved = fullScreenSavedGeometry
        fullScreenWindowId = null
        fullScreenSavedGeometry = null
        computeLayout()
        if (saved != null) {
            session.windows[id]?.geometry = saved.copyOf()
        }
        persist()
        publishState()
        if (syncNow) syncGeometry("exit-fullscreen")
    }

    /**
     * Enforces the Stage Manager 4-window-per-Stage cap: if the active Stage exceeds 4 windows,
     * evicts the oldest/bottom-most window(s) into standalone Stage(s) at the top of Recent Strip.
     */
    private fun enforceActiveStageLimit(): List<Int> {
        val activeId = session.activeGroupId
        val evictedTaskIds = ArrayList<Int>()
        while (true) {
            val visible = session.visibleWindowsOf(activeId)
            if (visible.size <= MAX_WINDOWS_PER_STAGE) break
            val oldest = visible.first()
            session.groups.forEach { it.windowIds.remove(oldest.id) }
            val solo = session.newGroup(oldest.label)
            solo.windowIds += oldest.id
            solo.lastActiveAt = now() - 1
            if (oldest.alive) evictedTaskIds += oldest.taskId
        }
        pruneEmptyGroups()
        return evictedTaskIds
    }

    /** Removes any non-active Stage that has 0 remaining windows (dynamic Stage lifecycle). */
    private fun pruneEmptyGroups() {
        val activeId = session.activeGroupId
        session.groups.removeAll { g -> g.id != activeId && g.windowIds.isEmpty() }
    }

    /** Called by the drag/resize gestures: geometry already in pixels of the workspace. */
    fun updateGeometryPx(windowId: String, rect: StageRect) {
        val window = session.windows[windowId] ?: return
        window.geometry = geometryFor(rect)
        publishState()
    }

    /** Commits the geometry to the real task; safe to call at gesture frame rate. */
    suspend fun commitGeometry(windowId: String) {
        val window = session.windows[windowId] ?: return
        val rect = pxRectFor(window.geometry)
        runCatching {
            StageRuntime.command(
                JSONObject().put("cmd", "bounds")
                    .put("taskId", window.taskId)
                    .put("l", rect.left).put("t", rect.top)
                    .put("r", rect.right).put("b", rect.bottom)
            )
        }.onFailure {
            if (it !is kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "commitGeometry failed: ${it.message}")
            }
        }
    }

    // ------------------------------------------------------------------ operations

    /**
     * iPad Dock tap semantics (P3):
     *  - If the app already has a window in the active group -> bring it to front & focus.
     *  - If the app already has a window in another group -> switch to that group & focus it.
     *  - Otherwise -> if the current active group is empty, open it here; if the current group
     *    already has windows, create a new group starting with this app and switch to it.
     *
     * Explicitly adding an app into an already-occupied current group is done via long-press drag
     * from the Dock into the central workspace ([openApp]).
     */
    suspend fun activateOrOpenApp(
        packageName: String,
        label: String,
        component: String
    ): Result<StageWindow> {
        if (dockAutoHide && dockRevealed) {
            dockRevealed = false
            computeLayout()
        }
        val activeId = session.activeGroupId
        // 1. Check active group first.
        val inActive = session.visibleWindowsOf(activeId).lastOrNull { it.packageName == packageName }
        if (inActive != null) {
            if (!inActive.alive) ensureGroupAlive(activeId)
            focusWindow(inActive.id)
            return Result.success(inActive)
        }
        // 2. Check other groups (most recently active first).
        for (group in session.sortedGroups()) {
            if (group.id == activeId) continue
            val match = session.visibleWindowsOf(group.id).lastOrNull { it.packageName == packageName }
            if (match != null) {
                activateGroup(group.id)
                focusWindow(match.id)
                return Result.success(match)
            }
        }
        // 3. Not open anywhere: reuse active group only if empty, otherwise create a fresh group.
        val currentHasWindows = session.visibleWindowsOf(activeId).isNotEmpty()
        if (currentHasWindows) {
            val fresh = session.newGroup(label)
            session.makeActive(fresh.id)
            persist()
            publishState()
            syncGeometry("dock-new-group")
        }
        return openApp(packageName, label, component, at = null)
    }

    /**
     * Dragging a recent-group tile from the left rail into the central workspace (P3):
     * moves the group's windows into the current active group, placing the front window at [at].
     */
    suspend fun addGroupToActiveGroup(sourceGroupId: String, at: android.graphics.PointF? = null): Boolean {
        if (dockAutoHide && dockRevealed) {
            dockRevealed = false
            computeLayout()
        }
        val activeId = session.activeGroupId
        if (sourceGroupId == activeId) return false
        val sourceGroup = session.group(sourceGroupId) ?: return false
        val windowIds = sourceGroup.windowIds.toList()
        if (windowIds.isEmpty()) return false

        val frontId = windowIds.last()
        windowIds.forEach { wid ->
            session.moveWindow(wid, activeId)
            val w = session.windows[wid] ?: return@forEach
            w.minimized = false
            if (wid == frontId && at != null) {
                val area = workspaceRect()
                if (area.width > 0 && area.height > 0) {
                    val halfW = (area.width * w.geometry.w / 2f).roundToInt()
                    val halfH = (area.height * w.geometry.h / 2f).roundToInt()
                    val targetRect = StageRect(
                        at.x.roundToInt() - halfW,
                        at.y.roundToInt() - halfH,
                        at.x.roundToInt() + halfW,
                        at.y.roundToInt() + halfH
                    )
                    val g = geometryFor(targetRect)
                    g.w = w.geometry.w
                    g.h = w.geometry.h
                    g.clamp()
                    w.geometry = g
                }
            }
        }
        // Remove the source group if it is now empty and we have more than one group.
        if (sourceGroup.windowIds.isEmpty() && session.groups.size > 1) {
            session.groups.remove(sourceGroup)
        }
        session.bringToFront(frontId)
        enforceActiveStageLimit()
        persist()
        publishState()
        ensureGroupAlive(activeId)
        syncGeometry("rail-drag-in")
        session.windows[frontId]?.let { front ->
            if (front.alive) {
                runCatching { setFocusable(front.taskId, true) }
                runCatching { StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", front.taskId)) }
            }
        }
        return true
    }

    /**
     * Opens an app directly into a specific (possibly non-active) group, e.g. when a Dock icon
     * is dropped onto a rail group tile.
     */
    suspend fun openAppIntoGroup(
        groupId: String,
        packageName: String,
        label: String,
        component: String
    ): Result<StageWindow> {
        if (groupId == session.activeGroupId) {
            return openApp(packageName, label, component, at = null)
        }
        activateGroup(groupId)
        return openApp(packageName, label, component, at = null)
    }

    /** Updates the visual drop-zone highlight while dragging a workspace window. */
    fun updateWindowDragTarget(x: Int, y: Int) {
        val state = _state.value
        val next = computeWindowDropHighlight(state, x, y)
        if (next != currentDropHighlight) {
            currentDropHighlight = next
            publishState()
        }
    }

    /** Updates the visual drop-zone highlight while dragging a Dock icon or rail group card. */
    fun updateExternalDragTarget(x: Int, y: Int, sourceGroupId: String? = null) {
        val state = _state.value
        val ws = state.workspace
        val next = when {
            ws.width > 0 && x >= ws.left && x <= ws.right && y >= ws.top && y <= ws.bottom ->
                DropHighlight(DropZoneKind.WORKSPACE, state.activeGroupId, ws, "加入当前工作台")
            state.railVisible -> {
                val tile = state.groups.firstOrNull { g ->
                    g.id != sourceGroupId &&
                        x >= g.rect.left && x <= g.rect.right && y >= g.rect.top && y <= g.rect.bottom
                }
                if (tile != null) {
                    DropHighlight(DropZoneKind.GROUP_TILE, tile.id, tile.rect, "加入 ${tile.title}")
                } else {
                    DropHighlight()
                }
            }
            else -> DropHighlight()
        }
        if (next != currentDropHighlight) {
            currentDropHighlight = next
            publishState()
        }
    }

    fun clearDropHighlight() {
        if (currentDropHighlight.kind == DropZoneKind.NONE) return
        currentDropHighlight = DropHighlight()
        publishState()
    }

    private fun computeWindowDropHighlight(state: WorkspaceState, x: Int, y: Int): DropHighlight {
        if (!state.railVisible) return DropHighlight()
        val add = state.addGroupRect
        if (add.width > 0 && x >= add.left && x <= add.right && y >= add.top && y <= add.bottom) {
            return DropHighlight(DropZoneKind.ADD_GROUP, null, add, "新建工作台")
        }
        val tile = state.groups.firstOrNull { g ->
            x >= g.rect.left && x <= g.rect.right && y >= g.rect.top && y <= g.rect.bottom
        }
        if (tile != null) {
            return DropHighlight(DropZoneKind.GROUP_TILE, tile.id, tile.rect, "移入 ${tile.title}")
        }
        val rail = state.rail
        if (rail.width > 0 && x >= rail.left && x <= rail.right && y >= rail.top && y <= rail.bottom) {
            return DropHighlight(DropZoneKind.RAIL_REMOVE, null, rail, "移出当前工作台")
        }
        return DropHighlight()
    }

    /**
     * Explicitly creates a new independent window for an app ONLY when Android actually supports
     * multiple instances for its launch Activity (P6). Never fakes multiple windows for singleTask
     * or singleInstance apps.
     */
    suspend fun openNewWindow(
        packageName: String,
        label: String,
        component: String
    ): Result<StageWindow> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("Workspace not attached"))
        if (!dev.stagelab.android.ui.supportsMultiInstance(ctx, packageName, component)) {
            return Result.failure(UnsupportedOperationException("$label 不支持多窗口实例"))
        }
        return openApp(packageName, label, component, at = null)
    }

    /** Returns all alive windows belonging to [packageName] across every group (P6). */
    fun windowsForPackage(packageName: String): List<StageWindow> =
        session.windows.values.filter { it.packageName == packageName && it.alive }

    /** Moves a window from any group into the active group and focuses it (P6 App Exposé). */
    suspend fun bringWindowToActiveGroup(windowId: String) {
        val activeId = session.activeGroupId
        val window = session.windows[windowId] ?: return
        val owner = session.groupOf(windowId)
        if (owner != null && owner.id != activeId) {
            session.moveWindow(windowId, activeId)
            if (owner.windowIds.isEmpty() && session.groups.size > 1) {
                session.groups.remove(owner)
            }
        }
        window.minimized = false
        session.bringToFront(windowId)
        persist()
        publishState()
        syncGeometry("expose-bring")
        runCatching {
            setFocusable(window.taskId, true)
            StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", window.taskId))
        }
    }

    suspend fun openApp(
        packageName: String,
        label: String,
        component: String,
        at: android.graphics.PointF? = null,
        extraName: String = "",
        extraValue: String = ""
    ): Result<StageWindow> {
        if (fullScreenWindowId != null) {
            exitFullScreen(syncNow = false)
        }
        val group = session.activeGroup()
        val existing = session.visibleWindowsOf(group.id).size
        val geometry = WindowSizing.defaultGeometry(appContext, packageName, component, workspaceRect(), existing, at)
        val rect = pxRectFor(geometry)
        // Glide the newly launched real task upward from the Dock edge into its final slot (P8).
        val launchRect = StageRect(
            rect.left,
            (rect.top + dp(44f)).coerceAtMost(workspaceRect().bottom - dp(200f)),
            rect.right,
            (rect.bottom + dp(44f)).coerceAtMost(workspaceRect().bottom)
        )
        return try {
            val command = JSONObject().put("cmd", "open")
                .put("id", newId("w"))
                .put("package", packageName)
                .put("activity", component)
                .put("title", label)
                .put("l", launchRect.left).put("t", launchRect.top)
                .put("r", launchRect.right).put("b", launchRect.bottom)
            if (extraName.isNotEmpty()) {
                command.put("extraName", extraName).put("extraValue", extraValue)
            }
            val data = StageRuntime.command(command)
            val taskId = data.optInt("taskId")
            val reused = data.optBoolean("reused")
            if (reused) {
                // The app refused a new task; show the one it already has (and adopt it into the
                // session if it was not tracked yet).
                val existing = session.windows.values.firstOrNull { it.taskId == taskId }
                if (existing != null) {
                    existing.alive = true
                    existing.minimized = false
                    session.bringToFront(existing.id)
                    runCatching {
                        StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", taskId))
                    }
                    persist()
                    publishState()
                    return Result.success(existing)
                }
            }
            val window = session.addWindow(
                groupId = group.id,
                packageName = packageName,
                component = data.optString("activity", component),
                label = label,
                taskId = data.optInt("taskId"),
                geometry = geometry
            )
            appContext?.let {
                dev.stagelab.android.ui.DockConfigStore.recordRecent(it, packageName)
                dockRefreshVersion++
            }
            if (dockAutoHide && dockRevealed) {
                dockRevealed = false
                computeLayout()
            }
            enforceActiveStageLimit()
            persist()
            syncGeometry("open")
            publishState()
            Result.success(window)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun defaultGeometryFor(index: Int, at: android.graphics.PointF?): WindowGeometry {
        val area = workspaceRect()
        val width = if (area.width > area.height) 0.52f else 0.62f
        val height = if (area.width > area.height) 0.72f else 0.56f
        if (at != null && area.width > 0) {
            val geometry = geometryFor(
                StageRect(
                    (at.x - area.width * width / 2).roundToInt(),
                    (at.y - area.height * height / 2).roundToInt(),
                    (at.x + area.width * width / 2).roundToInt(),
                    (at.y + area.height * height / 2).roundToInt()
                )
            )
            geometry.w = width
            geometry.h = height
            geometry.clamp()
            return geometry
        }
        val offset = (index % 4) * 0.06f
        return WindowGeometry(
            x = (0.5f - width / 2 + offset).coerceIn(0f, 1f - width),
            y = (0.5f - height / 2 + offset * 0.6f).coerceIn(0f, 1f - height),
            w = width,
            h = height
        )
    }

    suspend fun focusWindow(windowId: String) {
        val window = session.windows[windowId] ?: return
        session.bringToFront(windowId)
        session.windowIdsOwner(windowId)?.let { session.makeActive(it) }
        runCatching {
            StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", window.taskId))
        }
        publishState()
        persist()
    }

    /** Snaps a window to a standard iPad-style arrangement with 120fps spring transition. */
    suspend fun arrangeWindow(windowId: String, preset: WindowArrangePreset) {
        val window = session.windows[windowId] ?: return
        val fromRect = pxRectFor(window.geometry)
        val targetGeometry = when (preset) {
            WindowArrangePreset.LEFT_HALF -> WindowGeometry(0.01f, 0.01f, 0.485f, 0.98f)
            WindowArrangePreset.RIGHT_HALF -> WindowGeometry(0.505f, 0.01f, 0.485f, 0.98f)
            WindowArrangePreset.CENTER -> WindowGeometry(0.18f, 0.08f, 0.64f, 0.84f)
            WindowArrangePreset.FULL -> WindowGeometry(0.01f, 0.01f, 0.98f, 0.98f)
        }
        val toRect = pxRectFor(targetGeometry)
        val snap = dev.stagelab.android.windows.TaskSnapshotStore.bitmapFor(window.taskId)
        session.bringToFront(windowId)
        window.geometry = targetGeometry
        persist()
        publishState()

        val player = transitionPlayer
        kotlinx.coroutines.coroutineScope {
            if (window.alive) {
                launch {
                    runCatching { applyBounds(window.taskId, toRect) }
                    runCatching {
                        StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", window.taskId))
                    }
                }
            }
            if (player != null) {
                player(
                    listOf(
                        dev.stagelab.android.windows.StageTransitionOverlay.TransitionCard(
                            bitmap = snap,
                            fromRect = fromRect,
                            toRect = toRect,
                            fromTiltY = 0f,
                            toTiltY = 0f
                        )
                    ),
                    300L
                )
            }
        }
    }

    /** Multi-window group layout presets (P9 Window menu). */
    suspend fun arrangeActiveGroup(preset: GroupArrangePreset) {
        val visible = session.visibleWindowsOf(session.activeGroupId).filter { it.alive }
        if (visible.isEmpty()) return
        when (preset) {
            GroupArrangePreset.SIDE_BY_SIDE -> {
                if (visible.size == 1) {
                    arrangeWindow(visible.first().id, WindowArrangePreset.CENTER)
                    return
                }
                val rightWin = visible.last()
                val leftWin = visible[visible.size - 2]
                leftWin.geometry = WindowGeometry(0.01f, 0.01f, 0.485f, 0.98f)
                rightWin.geometry = WindowGeometry(0.505f, 0.01f, 0.485f, 0.98f)
            }
            GroupArrangePreset.CASCADE -> {
                visible.forEachIndexed { idx, w ->
                    val step = (idx % 5) * 0.055f
                    w.geometry = WindowGeometry(
                        x = (0.04f + step).coerceIn(0f, 0.36f),
                        y = (0.04f + step * 0.8f).coerceIn(0f, 0.28f),
                        w = 0.62f,
                        h = 0.74f
                    )
                }
            }
        }
        persist()
        publishState()
        syncGeometry("arrange-group")
    }

    /**
     * iPad semantics: minimizing removes the window from the current group and returns it to the
     * recent list as a single-window group of its own. The task stays alive and glides smoothly
     * into the left rail slot before settling (P4/P8).
     */
    suspend fun minimize(windowId: String) {
        if (fullScreenWindowId == windowId) {
            exitFullScreen(syncNow = false)
        }
        val window = session.windows[windowId] ?: return
        val sourceGroupId = session.groupOf(windowId)?.id ?: return
        val fromRect = pxRectFor(window.geometry)
        val railSlot = tileRect(0, max(session.groups.size, 1))
        // Read cached snapshot immediately (0ms delay; never block UI on capture!).
        val snap = if (window.alive) {
            dev.stagelab.android.windows.TaskSnapshotStore.bitmapFor(window.taskId)
        } else null
        window.minimized = false

        // Detach from the current group.
        session.groups.forEach { it.windowIds.remove(windowId) }

        // Put it at the top of the recent list as its own group (or reuse the existing singleton).
        val existingGroup = session.groups.firstOrNull { group ->
            group.id != sourceGroupId &&
                group.windowIds.size == 1 &&
                session.windowsOf(group.id).firstOrNull()?.packageName == window.packageName
        }
        val groupId = existingGroup?.id ?: run {
            val group = session.newGroup(window.label)
            group.id
        }
        session.group(groupId)!!.windowIds += windowId
        session.group(groupId)!!.lastActiveAt = now() - 1
        persist()
        publishState()

        val remaining = session.visibleWindowsOf(sourceGroupId).filter { it.alive }
        val player = transitionPlayer
        kotlinx.coroutines.coroutineScope {
            // Single-IPC hide + raise host + keep remaining windows in front, running IN PARALLEL with animation!
            launch {
                runCatching {
                    val hideArr = org.json.JSONArray().put(window.taskId)
                    val showArr = org.json.JSONArray()
                    remaining.forEach { w ->
                        val r = pxRectFor(w.geometry)
                        showArr.put(
                            JSONObject().put("taskId", w.taskId)
                                .put("l", r.left).put("t", r.top).put("r", r.right).put("b", r.bottom)
                        )
                    }
                    StageRuntime.command(
                        JSONObject().put("cmd", "switchGroup")
                            .put("hide", hideArr)
                            .put("show", showArr)
                            .put("hostComponent", "StageHostActivity")
                    )
                }
            }
            if (window.alive && railShouldShow() && railSlot != null && player != null) {
                player(
                    listOf(
                        dev.stagelab.android.windows.StageTransitionOverlay.TransitionCard(
                            bitmap = snap,
                            fromRect = fromRect,
                            toRect = railSlot,
                            fromTiltY = 0f,
                            toTiltY = 16f
                        )
                    ),
                    340L
                )
            }
        }
    }

    /** Restores a minimized window into the current group (drag or tap from the recent list). */
    suspend fun restore(windowId: String) {
        val window = session.windows[windowId] ?: return
        window.minimized = false
        val currentGroup = session.groupOf(windowId)
        if (currentGroup == null || currentGroup.id == session.activeGroupId) {
            // Already part of the active group: bring it back into the workspace.
            session.bringToFront(windowId)
        } else {
            // Coming from the recent list: move it into the active group.
            session.moveWindow(windowId, session.activeGroupId)
        }
        runCatching {
            setFocusable(window.taskId, true)
            StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", window.taskId))
        }
        persist()
        syncGeometry("restore")
        publishState()
    }

    suspend fun closeWindow(windowId: String) {
        val window = session.windows[windowId] ?: return
        val removed = runCatching {
            StageRuntime.command(JSONObject().put("cmd", "close").put("taskId", window.taskId))
        }
        // Only drop the window from the session when the task is really gone; otherwise the user
        // would lose a window that is still running on screen.
        if (removed.isFailure && window.alive) {
            Log.w(TAG, "close failed for ${window.label}: ${removed.exceptionOrNull()?.message}")
            publishState()
            return
        }
        if (fullScreenWindowId == windowId) {
            exitFullScreen(syncNow = false)
        }
        session.removeWindow(windowId)
        pruneEmptyGroups()
        persist()
        publishState()
        syncGeometry("close")
    }

    suspend fun moveWindowToGroup(windowId: String, groupId: String) {
        val window = session.windows[windowId] ?: return
        val fromActive = groupId != session.activeGroupId
        session.moveWindow(windowId, groupId)
        persist()
        publishState()
        if (fromActive) {
            // The window left the workspace: park it behind the host, un-focusable.
            runCatching { setFocusable(window.taskId, false) }
            runCatching { StageRuntime.command(JSONObject().put("cmd", "toBack").put("taskId", window.taskId)) }
        }
        syncGeometry("move-group")
    }

    /**
     * Drag-and-drop release point routing (P3).
     *
     * Called when a title-bar drag ends. Decides what the drop means:
     *  * over the rail / a group tile / the "+" button -> move the window to that group;
     *  * over the workspace -> it was just a move, nothing else to do.
     */
    suspend fun handleWindowDrop(windowId: String, x: Int, y: Int): Boolean {
        Log.d(TAG, "windowDrop id=$windowId at $x,$y")
        clearDropHighlight()
        val state = _state.value
        if (!state.railVisible) return false
        // "+" creates a new group and moves the window there.
        val add = state.addGroupRect
        if (add.width > 0 && x >= add.left && x <= add.right && y >= add.top && y <= add.bottom) {
            val group = createGroup()
            moveWindowToGroup(windowId, group.id)
            return true
        }
        // Over a group tile -> move to that group.
        val tile = state.groups.firstOrNull { g ->
            x >= g.rect.left && x <= g.rect.right && y >= g.rect.top && y <= g.rect.bottom
        }
        if (tile != null) {
            moveWindowToGroup(windowId, tile.id)
            return true
        }
        // Over the rail panel but not a tile -> move out of the current group into the recent list
        // (a singleton group like minimize does).
        val rail = state.rail
        if (rail.width > 0 && x >= rail.left && x <= rail.right && y >= rail.top && y <= rail.bottom) {
            minimize(windowId)
            return true
        }
        return false
    }

    fun createGroup(): StageGroup {
        val group = session.newGroup()
        session.makeActive(group.id)
        persist()
        publishState()
        return group
    }

    suspend fun activateGroup(groupId: String) {
        if (groupId == session.activeGroupId) return
        if (fullScreenWindowId != null) {
            exitFullScreen(syncNow = false)
        }
        val previousGroupId = session.activeGroupId
        val outgoingWindows = session.visibleWindowsOf(previousGroupId).filter { it.alive }
        val otherGroups = session.sortedGroups().filter { it.id != previousGroupId }
        val incomingIndex = otherGroups.indexOfFirst { it.id == groupId }.coerceAtLeast(0)
        val incomingSlot = tileRect(incomingIndex, max(otherGroups.size, 1))
        val topRailSlot = tileRect(0, max(otherGroups.size, 1))

        // Read cached snapshots immediately (0ms delay — NEVER block the switch on screencap!).
        val outgoingFront = outgoingWindows.lastOrNull()
        val outgoingSnap = outgoingFront?.let {
            dev.stagelab.android.windows.TaskSnapshotStore.bitmapFor(it.taskId)
        }

        // Portrait (or landscape auto-hide): selecting a group tucks the rail back.
        if (!landscape && railPinnedPortrait) {
            railPinnedPortrait = false
            computeLayout()
        } else if (landscape && railAutoHide && railTempRevealed) {
            railTempRevealed = false
            computeLayout()
        }

        session.makeActive(groupId)
        pruneEmptyGroups()
        persist()
        publishState()
        ensureGroupAlive(groupId)

        val incomingWindows = session.visibleWindowsOf(groupId).filter { it.alive }
        val incomingFront = incomingWindows.lastOrNull()
        val incomingSnap = incomingFront?.let {
            dev.stagelab.android.windows.TaskSnapshotStore.bitmapFor(it.taskId)
        }

        // Build batched single-IPC switchGroup payload: hide EVERY non-active group's alive tasks!
        val hideArr = org.json.JSONArray()
        session.groups.forEach { g ->
            if (g.id == groupId) return@forEach
            session.windowsOf(g.id).filter { it.alive }.forEach { hideArr.put(it.taskId) }
        }
        session.windows.values.filter { it.minimized && it.alive }.forEach { hideArr.put(it.taskId) }
        val showArr = org.json.JSONArray()
        incomingWindows.forEach { w ->
            val r = pxRectFor(w.geometry)
            showArr.put(
                JSONObject().put("taskId", w.taskId)
                    .put("l", r.left).put("t", r.top).put("r", r.right).put("b", r.bottom)
            )
        }

        val player = transitionPlayer
        val cards = ArrayList<dev.stagelab.android.windows.StageTransitionOverlay.TransitionCard>(2)
        if (topRailSlot != null && incomingSlot != null) {
            if (outgoingFront != null) {
                cards += dev.stagelab.android.windows.StageTransitionOverlay.TransitionCard(
                    bitmap = outgoingSnap,
                    fromRect = pxRectFor(outgoingFront.geometry),
                    toRect = topRailSlot,
                    fromTiltY = 0f,
                    toTiltY = 16f
                )
            }
            if (incomingFront != null) {
                cards += dev.stagelab.android.windows.StageTransitionOverlay.TransitionCard(
                    bitmap = incomingSnap,
                    fromRect = incomingSlot,
                    toRect = pxRectFor(incomingFront.geometry),
                    fromTiltY = 16f,
                    toTiltY = 0f
                )
            }
        }

        // Run single-IPC task switch IN PARALLEL with the 380ms 120fps spring animation!
        kotlinx.coroutines.coroutineScope {
            launch {
                runCatching {
                    StageRuntime.command(
                        JSONObject().put("cmd", "switchGroup")
                            .put("hide", hideArr)
                            .put("show", showArr)
                            .put("hostComponent", "StageHostActivity")
                    )
                }
            }
            if (player != null && cards.isNotEmpty()) {
                player(cards, 380L)
            }
        }

        incomingFront?.let { session.bringToFront(it.id) }
        publishState()

        // Capture fresh snapshot of the frontmost (unoccluded) window asynchronously after animation settles.
        scope.launch {
            delay(450L)
            if (activeGestureWindows.isEmpty()) {
                incomingFront?.let { w ->
                    dev.stagelab.android.windows.TaskSnapshotStore.capture(w.taskId, force = true)
                }
            }
        }
    }

    /**
     * Relaunches a group's applications when their tasks are really gone (process death, reboot,
     * or a window the user closed outside the stage). It refuses to launch when the task list is
     * unavailable or when an equivalent task already exists, which avoids duplicate windows after
     * a transient backend hiccup.
     */
    suspend fun ensureGroupAlive(groupId: String) {
        val group = session.group(groupId) ?: return
        val tasks = runCatching { StageRuntime.tasks() }.getOrNull() ?: return
        if (tasks.isEmpty()) return
        var changed = false
        session.windowsOf(groupId).forEach { window ->
            if (window.alive) return@forEach
            // Adopt an existing task of the same app before creating a new one.
            val owned = session.windows.values.map { it.taskId }.toSet()
            val existing = tasks.firstOrNull { task ->
                task.taskId !in owned &&
                    task.topActivity.startsWith(window.packageName + "/") &&
                    (window.component.isEmpty() || task.topActivity.contains(window.component))
            }
            if (existing != null) {
                window.taskId = existing.taskId
                window.alive = true
                window.minimized = false
                changed = true
                return@forEach
            }
            val rect = pxRectFor(window.geometry)
            val result = runCatching {
                StageRuntime.command(
                    JSONObject().put("cmd", "open")
                        .put("id", newId("w"))
                        .put("package", window.packageName)
                        .put("activity", window.component)
                        .put("title", window.label)
                        .put("l", rect.left).put("t", rect.top)
                        .put("r", rect.right).put("b", rect.bottom)
                )
            }
            val data = result.getOrNull() ?: return@forEach
            window.taskId = data.optInt("taskId")
            window.alive = true
            window.minimized = false
            changed = true
        }
        if (changed) {
            persist()
            publishState()
        }
    }

    suspend fun deleteGroup(groupId: String) {
        val group = session.group(groupId) ?: return
        if (session.groups.size <= 1) return
        group.windowIds.toList().forEach { closeWindow(it) }
        session.groups.remove(group)
        if (session.activeGroupId == groupId) {
            session.groups.firstOrNull()?.let { session.makeActive(it.id) }
        }
        persist()
        publishState()
        syncGeometry("delete-group")
    }

    /**
     * Switches to the adjacent recent workspace (P7 bottom horizontal swipe gesture).
     * [direction] > 0 switches to the most recent other group; < 0 cycles from the oldest group.
     */
    suspend fun switchAdjacentGroup(direction: Int): Boolean {
        val others = session.sortedGroups().filter { it.id != session.activeGroupId }
        if (others.isEmpty()) return false
        val target = if (direction >= 0) others.first() else others.last()
        activateGroup(target.id)
        return true
    }

    fun setRailVisible(visible: Boolean) {
        if (landscape && railAutoHide) {
            if (railTempRevealed == visible) return
            railTempRevealed = visible
        } else if (landscape) {
            if (railHiddenByUser == !visible) return
            railHiddenByUser = !visible
        } else {
            if (railPinnedPortrait == visible) return
            railPinnedPortrait = visible
        }
        computeLayout()
        publishState()
        scope.launch { runCatching { syncGeometry(if (visible) "rail-show" else "rail-hide") } }
    }

    fun toggleRail() = setRailVisible(!isRailVisible())

    fun isRailVisible(): Boolean = railShouldShow()

    // ------------------------------------------------------------------ sync

    /** Applies session geometry to the real tasks in a single batched IPC: active group free, other groups parked toBack. */
    suspend fun syncGeometry(reason: String) {
        if (applying) return
        applying = true
        try {
            val groups = session.groups
            val activeId = session.activeGroupId
            val hideArr = org.json.JSONArray()
            val showArr = org.json.JSONArray()

            groups.forEach { group ->
                if (group.id == activeId) return@forEach
                session.windowsOf(group.id).forEach { window ->
                    if (!window.alive) return@forEach
                    hideArr.put(window.taskId)
                }
            }
            session.windows.values.filter { it.minimized && it.alive }.forEach { window ->
                hideArr.put(window.taskId)
            }
            session.windowsOf(activeId).forEach { window ->
                if (!window.alive || window.minimized) return@forEach
                if (window.id in activeGestureWindows) return@forEach
                val rect = pxRectFor(window.geometry)
                showArr.put(
                    JSONObject().put("taskId", window.taskId)
                        .put("l", rect.left).put("t", rect.top).put("r", rect.right).put("b", rect.bottom)
                )
            }

            if (hideArr.length() > 0 || showArr.length() > 0) {
                runCatching {
                    StageRuntime.command(
                        JSONObject().put("cmd", "switchGroup")
                            .put("hide", hideArr)
                            .put("show", showArr)
                            .put("hostComponent", "StageHostActivity")
                    )
                }
            }
            Log.d(TAG, "syncGeometry($reason) groups=${groups.size} rail=${railShouldShow()} landscape=$landscape")
        } finally {
            applying = false
            publishState()
        }
    }

    private suspend fun applyBounds(taskId: Int, rect: StageRect) {
        StageRuntime.command(
            JSONObject().put("cmd", "bounds")
                .put("taskId", taskId)
                .put("l", rect.left).put("t", rect.top)
                .put("r", rect.right).put("b", rect.bottom)
        )
    }

    private suspend fun setHidden(taskId: Int, hidden: Boolean) {
        StageRuntime.command(
            JSONObject().put("cmd", if (hidden) "hide" else "show").put("taskId", taskId)
        )
    }

    private suspend fun setFocusable(taskId: Int, focusable: Boolean) {
        StageRuntime.command(
            JSONObject().put("cmd", "focusable").put("taskId", taskId).put("focusable", focusable)
        )
    }

    // ------------------------------------------------------------------ reconcile

    private suspend fun reconcileTasks() {
        if (screenW <= 0) return
        // If the backend is unavailable we must not touch the alive flags: doing so would make the
        // workspace relaunch applications that are actually still running.
        val tasks = runCatching { StageRuntime.tasks() }.getOrElse { return }
        val byId = tasks.associateBy { it.taskId }
        val owned = session.windows.values.map { it.taskId }.toMutableSet()
        var changed = false
        session.windows.values.forEach { window ->
            val task = byId[window.taskId]
            if (task != null) {
                if (!window.alive) {
                    window.alive = true
                    changed = true
                }
                return@forEach
            }
            // The task id is gone. Adopt an unowned task of the same app if there is one (this is
            // what happens when the system restarts a process), otherwise mark the window closed.
            val orphan = tasks.firstOrNull { candidate ->
                candidate.taskId !in owned &&
                    candidate.topActivity.startsWith(window.packageName + "/") &&
                    (window.component.isEmpty() || candidate.topActivity.contains(window.component))
            }
            if (orphan != null && orphan.windowingMode == 5) {
                window.taskId = orphan.taskId
                window.alive = true
                owned += orphan.taskId
                changed = true
            } else if (window.alive) {
                window.alive = false
                changed = true
            }
        }
        if (changed) {
            persist()
            publishState()
        }
        // Sync active group z-order & focus from the real system task stack (getTasks returns top-to-bottom).
        val activeGroup = session.activeGroup()
        val taskOrder = tasks.mapIndexed { idx, t -> t.taskId to idx }.toMap()
        val aliveIds = activeGroup.windowIds.filter { id ->
            val w = session.windows[id]
            w != null && w.alive && !w.minimized && taskOrder.containsKey(w.taskId)
        }
        if (aliveIds.size > 1) {
            // Bottom-most (largest index in getTasks) first, topmost (smallest index) last.
            val sortedAlive = aliveIds.sortedByDescending { id ->
                taskOrder[session.windows[id]?.taskId] ?: Int.MAX_VALUE
            }
            if (sortedAlive != aliveIds) {
                val otherIds = activeGroup.windowIds.filterNot { it in aliveIds }
                activeGroup.windowIds.clear()
                activeGroup.windowIds.addAll(otherIds)
                activeGroup.windowIds.addAll(sortedAlive)
                publishState()
            }
        } else {
            val focusedTask = tasks.firstOrNull { it.focused }?.taskId ?: -1
            val focusedWindow = session.windows.values.firstOrNull { it.taskId == focusedTask }
            if (focusedWindow != null && session.windowsOf(session.activeGroupId).lastOrNull()?.id != focusedWindow.id) {
                session.bringToFront(focusedWindow.id)
                publishState()
            }
        }
        // Refresh TaskSnapshot for the frontmost (unoccluded) active window periodically.
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSnapshotPollMs > 12000L && activeGestureWindows.isEmpty()) {
            lastSnapshotPollMs = nowMs
            session.visibleWindowsOf(session.activeGroupId).lastOrNull { it.alive }?.let { front ->
                scope.launch {
                    dev.stagelab.android.windows.TaskSnapshotStore.capture(front.taskId, force = false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ state

    private fun publishState() {
        val dock = dockRect()
        val workspace = workspaceRect()
        val activeId = session.activeGroupId
        val groups = session.sortedGroups()
        // Recent Strip: up to 4 most recently used non-active Stages (dynamic Stage model).
        val otherGroups = groups
            .filter { it.id != activeId && session.visibleWindowsOf(it.id).isNotEmpty() }
            .take(MAX_RECENT_STAGES)
        val tiles = otherGroups.mapIndexedNotNull { otherIndex, group ->
            val rect = tileRect(otherIndex, otherGroups.size) ?: return@mapIndexedNotNull null
            val visibleWins = session.visibleWindowsOf(group.id)
            GroupTile(
                id = group.id,
                title = group.title,
                windowCount = visibleWins.size,
                active = false,
                rect = rect,
                live = false,
                previews = visibleWins.takeLast(3).map { w ->
                    GroupWindowPreview(
                        id = w.id,
                        taskId = w.taskId,
                        packageName = w.packageName,
                        label = w.label
                    )
                }
            )
        }
        val windows = session.windowsOf(activeId)
            .filter { it.alive }
            .mapNotNull { window ->
                val rect = pxRectFor(window.geometry)
                WindowTile(
                    id = window.id,
                    taskId = window.taskId,
                    title = window.label,
                    packageName = window.packageName,
                    rect = rect,
                    focused = session.windowsOf(activeId).lastOrNull { !it.minimized && it.alive }?.id == window.id,
                    minimized = window.minimized,
                    alive = window.alive
                )
            }
        val minimized = windows.filter { it.minimized }
        val railShown = railShouldShow()
        val activePackages = windows.filterNot { it.minimized }.map { it.packageName }.toSet()
        val runningPackages = session.windows.values.filter { it.alive }.map { it.packageName }.toSet()
        _state.value = WorkspaceState(
            ready = screenW > 0,
            activeGroupId = activeId,
            activeGroupTitle = session.activeGroup().title,
            groups = if (railShown) tiles else emptyList(),
            windows = windows.filterNot { it.minimized },
            minimized = minimized,
            railVisible = railShown,
            landscape = landscape,
            workspace = workspace,
            dock = dock,
            // iPad Stage Manager has no "+" button or scroll chevron buttons in the left rail.
            addGroupRect = StageRect(0, 0, 0, 0),
            rail = railRect() ?: StageRect(0, 0, 0, 0),
            railUpRect = StageRect(0, 0, 0, 0),
            railDownRect = StageRect(0, 0, 0, 0),
            dropHighlight = currentDropHighlight,
            dockVisible = dockShouldShow(),
            dockAutoHide = dockAutoHide,
            railAutoHide = railAutoHide,
            fullScreenWindowId = fullScreenWindowId,
            fullScreenBounds = fullScreenRect(),
            activePackages = activePackages,
            runningPackages = runningPackages,
            dockRefreshVersion = dockRefreshVersion,
            backend = StageRuntime.state.value.backend,
            error = null
        )
    }
}

/** Helper: the group that currently owns a window. */
private fun StageSession.windowIdsOwner(windowId: String): String? =
    groups.firstOrNull { it.windowIds.contains(windowId) }?.id

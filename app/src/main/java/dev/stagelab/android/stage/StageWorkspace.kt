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

data class GroupTile(
    val id: String,
    val title: String,
    val windowCount: Int,
    val active: Boolean,
    val rect: StageRect,
    val live: Boolean
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var store: StageSessionStore? = null
    private var pollJob: Job? = null
    private var applying = false

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

    fun isLandscape(): Boolean = landscape

    private fun railShouldShow(): Boolean = if (landscape) !railHiddenByUser else railPinnedPortrait

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
        val railLeft = dp(12f)
        val rail = StageRect(
            left = railLeft,
            top = statusInset + dp(10f),
            right = railLeft + railWidthPx(),
            bottom = dock.top - dp(12f)
        )
        val workspaceLeft = if (railShouldShow()) rail.right + dp(14f) else dp(18f)
        val workspace = StageRect(
            left = workspaceLeft,
            top = statusInset + dp(10f),
            right = screenW - dp(18f),
            bottom = dock.top - dp(12f)
        )
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

    fun railWidthPx(): Int = dp(238f)

    fun railRect(): StageRect? = layout?.rail

    fun workspaceRect(): StageRect = layout?.workspace ?: StageRect(0, 0, 0, 0)

    fun dockRect(): StageRect = layout?.dock ?: StageRect(0, 0, 0, 0)

    /** Vertical scroll of the rail, in pixels. */
    fun scrollRail(delta: Float) {
        val rail = railRect() ?: return
        val items = session.sortedGroups().size - 1
        if (items <= 0) return
        val perItem = dp(256f) + dp(12f)
        val maxScroll = (items * perItem - rail.height).coerceAtLeast(0).toFloat()
        val next = (railScroll - delta).coerceIn(0f, maxScroll)
        if (next == railScroll) return
        railScroll = next
        publishState()
        scope.launch { runCatching { syncGeometry("rail-scroll") } }
    }

    /**
     * Rail slots: the most recent group gets a full live-preview tile (a real task window fits the
     * system minimum), the older groups get compact tiles (icon + name). A live preview is not
     * possible for every tile because Android enforces a ~220dp minimum freeform window size.
     */
    fun tileRect(index: Int, count: Int): StageRect? {
        val rail = railRect() ?: return null
        if (count <= 0) return null
        val gap = dp(12f)
        val liveHeight = dp(256f)
        val compactHeight = dp(64f)
        val rect = if (index == 0) {
            StageRect(rail.left, rail.top - railScroll.roundToInt(), rail.right, rail.top - railScroll.roundToInt() + liveHeight)
        } else {
            val top = rail.top + liveHeight + gap + (index - 1) * (compactHeight + dp(8f)) - railScroll.roundToInt()
            StageRect(rail.left, top, rail.right, top + compactHeight)
        }
        if (rect.bottom > rail.bottom + 1 || rect.top < rail.top - 1) return null
        return rect
    }

    fun isLiveRailSlot(index: Int): Boolean = index == 0

    fun railCanScroll(): Boolean {
        val rail = railRect() ?: return false
        val items = (session.sortedGroups().size - 1).coerceAtLeast(0)
        return items * (dp(256f) + dp(12f)) > rail.height + dp(4f)
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
        publishState()
    }

    fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { reconcileTasks() }
                delay(500)
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

    suspend fun openApp(
        packageName: String,
        label: String,
        component: String,
        at: android.graphics.PointF? = null,
        extraName: String = "",
        extraValue: String = ""
    ): Result<StageWindow> {
        val group = session.activeGroup()
        val existing = session.visibleWindowsOf(group.id).size
        val geometry = defaultGeometryFor(existing, at)
        val rect = pxRectFor(geometry)
        return try {
            val command = JSONObject().put("cmd", "open")
                .put("id", newId("w"))
                .put("package", packageName)
                .put("activity", component)
                .put("title", label)
                .put("l", rect.left).put("t", rect.top)
                .put("r", rect.right).put("b", rect.bottom)
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

    /**
     * iPad semantics: minimizing removes the window from the current group and returns it to the
     * recent list as a single-window group of its own. The task stays alive; it can be dragged
     * back into any group later.
     */
    suspend fun minimize(windowId: String) {
        val window = session.windows[windowId] ?: return
        val sourceGroupId = session.groupOf(windowId)?.id ?: return
        window.minimized = false

        // Detach from the current group.
        session.groups.forEach { it.windowIds.remove(windowId) }

        // Park the task behind the stage host (it must stay alive and hidden from the workspace).
        runCatching {
            setFocusable(window.taskId, false)
            StageRuntime.command(JSONObject().put("cmd", "toBack").put("taskId", window.taskId))
        }

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
        session.group(groupId)!!.lastActiveAt = now() - 1 // just below the active group
        persist()
        publishState()

        // Promote the next window in the source group.
        session.visibleWindowsOf(sourceGroupId).lastOrNull()?.let { next ->
            runCatching { StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", next.taskId)) }
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
        session.removeWindow(windowId)
        persist()
        publishState()
        syncGeometry("close")
    }

    suspend fun moveWindowToGroup(windowId: String, groupId: String) {
        session.moveWindow(windowId, groupId)
        persist()
        syncGeometry("move-group")
        publishState()
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
        // Portrait: the rail is a drawer, so selecting a group closes it and the workspace expands.
        if (!landscape && railPinnedPortrait) {
            railPinnedPortrait = false
            computeLayout()
        }
        session.makeActive(groupId)
        persist()
        publishState()
        ensureGroupAlive(groupId)
        syncGeometry("activate-group")
        // Focus the front window of the newly active group.
        session.visibleWindowsOf(groupId).lastOrNull()?.let { front ->
            runCatching { StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", front.taskId)) }
            session.bringToFront(front.id)
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

    fun setRailVisible(visible: Boolean) {
        if (landscape) {
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

    /** Applies session geometry to the real tasks: active group free, other groups parked in rail. */
    suspend fun syncGeometry(reason: String) {
        if (applying) return
        applying = true
        try {
            val groups = session.groups
            val activeId = session.activeGroupId
            groups.forEachIndexed { groupIndex, group ->
                val isActive = group.id == activeId
                val windows = session.windowsOf(group.id)
                if (windows.isEmpty()) return@forEachIndexed
                if (isActive) {
                    windows.forEach { window ->
                        if (!window.alive) return@forEach
                        if (window.minimized) {
                            // Minimized windows live in the recent list, not in the workspace.
                            runCatching { setFocusable(window.taskId, false) }
                            runCatching { StageRuntime.command(JSONObject().put("cmd", "toBack").put("taskId", window.taskId)) }
                            return@forEach
                        }
                        val rect = pxRectFor(window.geometry)
                        runCatching { setFocusable(window.taskId, true) }
                        runCatching { applyBounds(window.taskId, rect) }
                    }
                } else if (railShouldShow()) {
                    val otherGroups = session.sortedGroups()
                        .filter { it.id != activeId }
                        .filter { group -> session.visibleWindowsOf(group.id).isNotEmpty() }
                    val slot = otherGroups.indexOfFirst { it.id == group.id }
                    val rect = tileRect(slot.coerceAtLeast(0), otherGroups.size)
                    if (rect == null || slot > 0) {
                        // Compact slot (or scrolled out of view): keep the task alive but behind the
                        // stage host. Only the most recent group gets a live preview tile.
                        windows.forEach { window ->
                            if (!window.alive) return@forEach
                            runCatching { setFocusable(window.taskId, false) }
                            runCatching {
                                StageRuntime.command(JSONObject().put("cmd", "toBack").put("taskId", window.taskId))
                            }
                        }
                        return@forEachIndexed
                    }
                    val inner = tileInner(rect)
                    windows.forEach { window ->
                        if (!window.alive) return@forEach
                        // A non focusable task cannot be raised above the stage host, which would
                        // leave a black hole where the live preview should be.
                        runCatching { setFocusable(window.taskId, true) }
                        runCatching { StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", window.taskId)) }
                        runCatching { applyBounds(window.taskId, inner) }
                    }
                } else {
                    windows.forEach { window ->
                        if (!window.alive) return@forEach
                        runCatching { setFocusable(window.taskId, false) }
                        runCatching { StageRuntime.command(JSONObject().put("cmd", "toBack").put("taskId", window.taskId)) }
                    }
                }
            }
            // Minimized windows sit in the recent list, never in the workspace.
            session.windows.values.filter { it.minimized && it.alive }.forEach { window ->
                runCatching { setFocusable(window.taskId, false) }
                runCatching { StageRuntime.command(JSONObject().put("cmd", "toBack").put("taskId", window.taskId)) }
            }
            // The focused window must be brought back to the front after parking others.
            session.visibleWindowsOf(activeId).lastOrNull()?.let { front ->
                runCatching { StageRuntime.command(JSONObject().put("cmd", "focus").put("taskId", front.taskId)) }
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
        // Focus follows the system task list so the UI can show the active window.
        val focusedTask = tasks.firstOrNull { it.focused }?.taskId ?: -1
        val focusedWindow = session.windows.values.firstOrNull { it.taskId == focusedTask }
        if (focusedWindow != null && session.windowsOf(session.activeGroupId).lastOrNull()?.id != focusedWindow.id) {
            session.bringToFront(focusedWindow.id)
            publishState()
        }
    }

    // ------------------------------------------------------------------ state

    private fun publishState() {
        val dock = dockRect()
        val workspace = workspaceRect()
        val activeId = session.activeGroupId
        val groups = session.sortedGroups()
        // Recent list: every non-active group (minimized windows live here as singleton groups).
        val tiles = groups.mapIndexedNotNull { index, group ->
            if (group.id == activeId) return@mapIndexedNotNull null
            val otherIndex = groups.filter { it.id != activeId }.indexOfFirst { it.id == group.id }
            val rect = tileRect(otherIndex.coerceAtLeast(0), max(groups.size - 1, 1))
                ?: return@mapIndexedNotNull null
            GroupTile(
                id = group.id,
                title = group.title,
                windowCount = session.visibleWindowsOf(group.id).size,
                active = false,
                rect = rect,
                live = isLiveRailSlot(otherIndex)
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
        // "+" button sits after the rail content, or at the rail top when it is empty.
        val railLeft = railRect()?.left ?: dp(12f)
        val railBottom = if (railShown && tiles.isNotEmpty()) {
            tiles.maxOf { it.rect.bottom }
        } else {
            railRect()?.top ?: (workspace.top + dp(8f))
        }
        val addSize = dp(44f)
        val addRect = StageRect(
            left = railLeft,
            top = railBottom + dp(12f),
            right = railLeft + addSize,
            bottom = railBottom + dp(12f) + addSize
        )
        val scrollable = railShown && railCanScroll()
        val chevron = dp(38f)
        val railBottomEdge = railRect()?.bottom ?: 0
        val railRightEdge = railRect()?.right ?: 0
        val upRect = if (scrollable) {
            StageRect(railRightEdge - chevron - dp(8f), railBottomEdge - chevron * 2 - dp(16f), railRightEdge - dp(8f), railBottomEdge - chevron - dp(16f))
        } else StageRect(0, 0, 0, 0)
        val downRect = if (scrollable) {
            StageRect(railRightEdge - chevron - dp(8f), railBottomEdge - chevron - dp(8f), railRightEdge - dp(8f), railBottomEdge - dp(8f))
        } else StageRect(0, 0, 0, 0)
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
            addGroupRect = if (railShown) addRect else StageRect(0, 0, 0, 0),
            rail = railRect() ?: StageRect(0, 0, 0, 0),
            railUpRect = upRect,
            railDownRect = downRect,
            backend = StageRuntime.state.value.backend,
            error = null
        )
    }
}

/** Helper: the group that currently owns a window. */
private fun StageSession.windowIdsOwner(windowId: String): String? =
    groups.firstOrNull { it.windowIds.contains(windowId) }?.id

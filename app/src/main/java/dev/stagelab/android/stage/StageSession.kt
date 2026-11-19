package dev.stagelab.android.stage

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Normalised geometry inside the workspace area (0..1). */
data class WindowGeometry(
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float
) {
    fun clamp() {
        w = w.coerceIn(MIN_W, 1f)
        h = h.coerceIn(MIN_H, 1f)
        x = x.coerceIn(0f, 1f - w)
        y = y.coerceIn(0f, 1f - h)
    }

    fun copyOf(): WindowGeometry = WindowGeometry(x, y, w, h)

    companion object {
        const val MIN_W = 0.25f
        const val MIN_H = 0.22f
    }
}

/**
 * A single application window inside a stage group.
 *
 * [id] is the durable identity (it survives task restarts); [taskId] is only the current runtime
 * handle in ActivityTaskManager.
 */
data class StageWindow(
    val id: String,
    val packageName: String,
    val component: String,
    var label: String,
    var taskId: Int,
    var geometry: WindowGeometry,
    var minimized: Boolean = false,
    var alive: Boolean = true
)

data class StageGroup(
    val id: String,
    var title: String,
    val windowIds: MutableList<String> = mutableListOf(),
    var lastActiveAt: Long = 0L
)

/**
 * The whole workspace: an ordered list of groups, and the windows they own.
 *
 * The list order inside a group is the z-order (last = front). Groups are ordered by recency.
 */
class StageSession(
    var activeGroupId: String,
    val groups: MutableList<StageGroup> = mutableListOf(),
    val windows: MutableMap<String, StageWindow> = mutableMapOf()
) {

    fun activeGroup(): StageGroup = groups.firstOrNull { it.id == activeGroupId } ?: ensureGroup()

    fun group(id: String): StageGroup? = groups.firstOrNull { it.id == id }

    fun windowsOf(groupId: String): List<StageWindow> =
        group(groupId)?.windowIds?.mapNotNull { windows[it] } ?: emptyList()

    fun visibleWindowsOf(groupId: String): List<StageWindow> = windowsOf(groupId).filterNot { it.minimized }

    fun ensureGroup(title: String = ""): StageGroup {
        groups.firstOrNull { it.id == activeGroupId }?.let { return it }
        val group = groups.firstOrNull()
        if (group != null) {
            activeGroupId = group.id
            return group
        }
        val created = StageGroup(newId("g"), title.ifBlank { "工作区 1" }, lastActiveAt = now())
        groups += created
        activeGroupId = created.id
        return created
    }

    fun newGroup(title: String = ""): StageGroup {
        val group = StageGroup(
            id = newId("g"),
            title = title.ifBlank { "工作区 ${groups.size + 1}" },
            lastActiveAt = now()
        )
        groups += group
        return group
    }

    fun addWindow(
        groupId: String,
        packageName: String,
        component: String,
        label: String,
        taskId: Int,
        geometry: WindowGeometry
    ): StageWindow {
        val group = group(groupId) ?: ensureGroup()
        val window = StageWindow(
            id = newId("w"),
            packageName = packageName,
            component = component,
            label = label,
            taskId = taskId,
            geometry = geometry
        )
        windows[window.id] = window
        group.windowIds += window.id
        group.lastActiveAt = now()
        return window
    }

    fun removeWindow(windowId: String) {
        windows.remove(windowId)
        groups.forEach { it.windowIds.remove(windowId) }
    }

    fun moveWindow(windowId: String, targetGroupId: String) {
        val window = windows[windowId] ?: return
        if (targetGroupId == groupOf(windowId)?.id) return
        groups.forEach { it.windowIds.remove(windowId) }
        val target = group(targetGroupId) ?: ensureGroup()
        target.windowIds += windowId
        target.lastActiveAt = now()
        // A moved window keeps its geometry but is nudged into the target workspace.
        window.minimized = false
    }

    fun groupOf(windowId: String): StageGroup? = groups.firstOrNull { it.windowIds.contains(windowId) }

    fun bringToFront(windowId: String) {
        val group = groupOf(windowId) ?: return
        group.windowIds.remove(windowId)
        group.windowIds += windowId
        group.lastActiveAt = now()
    }

    fun makeActive(groupId: String) {
        if (group(groupId) == null) return
        activeGroupId = groupId
        group(groupId)!!.lastActiveAt = now()
    }

    fun sortedGroups(): List<StageGroup> = groups.sortedByDescending { it.lastActiveAt }

    fun hasAnyWindow(): Boolean = windows.isNotEmpty()

    // ------------------------------------------------------------------ persistence

    fun toJson(): JSONObject {
        val groupArray = JSONArray()
        groups.forEach { group ->
            val ids = JSONArray()
            group.windowIds.forEach { if (windows.containsKey(it)) ids.put(it) }
            groupArray.put(
                JSONObject()
                    .put("id", group.id)
                    .put("title", group.title)
                    .put("lastActiveAt", group.lastActiveAt)
                    .put("windows", ids)
            )
        }
        val windowObject = JSONObject()
        windows.forEach { (id, window) ->
            windowObject.put(
                id,
                JSONObject()
                    .put("package", window.packageName)
                    .put("component", window.component)
                    .put("label", window.label)
                    .put("taskId", window.taskId)
                    .put("minimized", window.minimized)
                    .put("x", window.geometry.x.toDouble())
                    .put("y", window.geometry.y.toDouble())
                    .put("w", window.geometry.w.toDouble())
                    .put("h", window.geometry.h.toDouble())
            )
        }
        return JSONObject()
            .put("version", 2)
            .put("activeGroupId", activeGroupId)
            .put("groups", groupArray)
            .put("windows", windowObject)
    }

    companion object {
        fun createDefault(): StageSession {
            val session = StageSession(activeGroupId = "")
            session.ensureGroup("工作区 1")
            return session
        }

        fun fromJson(json: JSONObject): StageSession? = runCatching {
            if (json.optInt("version") != 2) return null
            val session = StageSession(activeGroupId = json.optString("activeGroupId"))
            val windowObject = json.optJSONObject("windows") ?: JSONObject()
            windowObject.keys().forEach { id ->
                val item = windowObject.optJSONObject(id) ?: return@forEach
                session.windows[id] = StageWindow(
                    id = id,
                    packageName = item.optString("package"),
                    component = item.optString("component"),
                    label = item.optString("label"),
                    taskId = item.optInt("taskId"),
                    geometry = WindowGeometry(
                        item.optDouble("x", 0.1).toFloat(),
                        item.optDouble("y", 0.1).toFloat(),
                        item.optDouble("w", 0.6).toFloat(),
                        item.optDouble("h", 0.6).toFloat()
                    ),
                    minimized = item.optBoolean("minimized"),
                    alive = false
                )
            }
            val groupArray = json.optJSONArray("groups") ?: JSONArray()
            for (index in 0 until groupArray.length()) {
                val item = groupArray.optJSONObject(index) ?: continue
                val group = StageGroup(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    lastActiveAt = item.optLong("lastActiveAt")
                )
                val ids = item.optJSONArray("windows") ?: JSONArray()
                for (windowIndex in 0 until ids.length()) {
                    val windowId = ids.optString(windowIndex)
                    if (session.windows.containsKey(windowId)) group.windowIds += windowId
                }
                session.groups += group
            }
            // Drop windows whose group disappeared.
            val owned = session.groups.flatMap { it.windowIds }.toSet()
            session.windows.keys.filter { it !in owned }.forEach { session.windows.remove(it) }
            session.ensureGroup("工作区 1")
            session
        }.getOrNull()
    }
}

internal fun newId(prefix: String): String = prefix + "-" + UUID.randomUUID().toString().take(8)

internal fun now(): Long = System.currentTimeMillis()

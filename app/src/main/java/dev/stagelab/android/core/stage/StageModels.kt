package dev.stagelab.android.core.stage

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Pixel rectangle used by the stage layout engine. */
data class StageRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun toAndroid(): android.graphics.Rect = android.graphics.Rect(left, top, right, bottom)

    fun toJson(): JSONObject = JSONObject()
        .put("l", left).put("t", top).put("r", right).put("b", bottom)

    companion object {
        fun from(json: JSONObject?): StageRect? {
            if (json == null) return null
            return StageRect(json.optInt("l"), json.optInt("t"), json.optInt("r"), json.optInt("b"))
        }
    }
}

data class StageWindowView(
    val id: String,
    val packageName: String,
    val title: String,
    val activity: String,
    val taskId: Int,
    val bounds: StageRect?,
    val focused: Boolean,
    val visible: Boolean,
    val alive: Boolean
)

data class StageStateView(
    val connected: Boolean = false,
    val backend: String = "未连接",
    val identity: String = "",
    val freeform: Boolean = false,
    val windows: List<StageWindowView> = emptyList(),
    val focusedTaskId: Int = -1,
    val message: String? = null,
    val lastRefreshMs: Long = 0L,
    val capabilityNotes: List<String> = emptyList(),
    val reconnecting: Boolean = false
) {
    val mainWindow: StageWindowView?
        get() = windows.firstOrNull { it.taskId == focusedTaskId && it.alive }
            ?: windows.firstOrNull { it.alive }

    val previewWindows: List<StageWindowView>
        get() = windows.filter { it.alive && it.taskId != mainWindow?.taskId }
}

/**
 * iPad Stage Manager style geometry.
 *
 * The device is a 1680 x 2520 tablet at 420 dpi. Everything is computed in pixels from dp so the
 * same rules work in landscape, on other tablets, and in freeform windows.
 */
object StageLayoutEngine {

    data class Layout(
        val main: StageRect,
        val previews: List<StageRect>,
        val dock: StageRect,
        val rail: StageRect,
        val landscape: Boolean,
        /** How many preview windows actually fit in the rail; the rest are parked. */
        val previewCapacity: Int
    )

    fun compute(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        previewCount: Int,
        statusInsetPx: Int = 0,
        navigationInsetPx: Int = 0
    ): Layout {
        fun dp(value: Float): Int = (value * density).toInt()

        val landscape = widthPx > heightPx
        val margin = dp(if (landscape) 18f else 14f)
        val gap = dp(10f)
        val dockHeight = dp(if (landscape) 66f else 84f)
        // Freeform tasks have a system minimum size (220dp on this build). The rail must stay above
        // it, otherwise WM clamps the window and pushes it off the right edge.
        val railWidth = dp(if (landscape) 244f else 232f)
        val topInset = max(statusInsetPx, dp(10f))
        val bottomInset = max(navigationInsetPx, dp(8f))

        val dock = StageRect(
            left = margin,
            top = heightPx - bottomInset - dockHeight,
            right = widthPx - margin,
            bottom = heightPx - bottomInset
        )
        // Leave room for the stage chrome (wordmark, backend pill, diagnostics buttons).
        val stageTop = topInset + dp(if (landscape) 52f else 66f)
        val stageBottom = dock.top - gap
        val rail = StageRect(
            left = widthPx - margin - railWidth,
            top = stageTop,
            right = widthPx - margin,
            bottom = stageBottom
        )
        val main = StageRect(
            left = margin,
            top = stageTop,
            right = rail.left - gap,
            bottom = stageBottom
        )

        val previews = ArrayList<StageRect>(previewCount)
        var capacity = 0
        if (previewCount > 0) {
            val available = rail.height
            val minSlot = dp(226f)
            capacity = (((available + gap) / (minSlot + gap)).coerceAtLeast(0))
                .coerceAtMost(previewCount)
            if (capacity > 0) {
                val itemHeight = (available - gap * (capacity - 1)) / capacity
                for (index in 0 until capacity) {
                    val top = rail.top + index * (itemHeight + gap)
                    previews += StageRect(
                        left = rail.left,
                        top = top,
                        right = rail.right,
                        bottom = min(top + itemHeight, rail.bottom)
                    )
                }
            }
        }
        return Layout(
            main = main,
            previews = previews,
            dock = dock,
            rail = rail,
            landscape = landscape,
            previewCapacity = capacity
        )
    }
}

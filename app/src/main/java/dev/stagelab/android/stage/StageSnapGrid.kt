package dev.stagelab.android.stage

import dev.stagelab.android.core.stage.StageRect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Discrete iPadOS Stage Manager resize preset tiers ("预设档位 / 网格吸附").
 *
 * Instead of pixel-by-pixel freeform resizing (which triggers continuous Activity relayouts and
 * UI crashes on Android apps), dragging a bottom corner snaps cleanly to curated aspect-safe
 * tiers, including a Full-Screen tier that triggers rail + Dock auto-retreat.
 */
object StageSnapGrid {

    data class SnapPreset(
        val id: String,
        val wFraction: Float,
        val hFraction: Float,
        val fullScreen: Boolean = false
    )

    data class SnapResult(
        val preset: SnapPreset,
        val rect: StageRect
    )

    private val LANDSCAPE_PRESETS = listOf(
        SnapPreset("compact", 0.36f, 0.78f),
        SnapPreset("split", 0.485f, 0.94f),
        SnapPreset("square", 0.54f, 0.68f),
        SnapPreset("standard", 0.64f, 0.82f),
        SnapPreset("video", 0.72f, 0.66f),
        SnapPreset("wide", 0.80f, 0.88f),
        SnapPreset("large", 0.95f, 0.95f),
        SnapPreset("fullscreen", 1.00f, 1.00f, fullScreen = true)
    )

    private val PORTRAIT_PRESETS = listOf(
        SnapPreset("compact", 0.66f, 0.44f),
        SnapPreset("medium", 0.82f, 0.58f),
        SnapPreset("tall", 0.90f, 0.74f),
        SnapPreset("large", 0.96f, 0.90f),
        SnapPreset("fullscreen", 1.00f, 1.00f, fullScreen = true)
    )

    /**
     * Snaps a raw dragged rectangle (anchored at top-left for bottom-right resize, or top-right for
     * bottom-left resize) to the nearest preset tier within [workspace] / [fullScreenBounds].
     */
    fun snap(
        rawRect: StageRect,
        anchorRight: Boolean,
        workspace: StageRect,
        fullScreenBounds: StageRect,
        minWidthPx: Int,
        minHeightPx: Int
    ): SnapResult {
        val wsW = workspace.width.coerceAtLeast(1)
        val wsH = workspace.height.coerceAtLeast(1)
        val presets = if (wsW >= wsH) LANDSCAPE_PRESETS else PORTRAIT_PRESETS

        val rawWf = rawRect.width.toFloat() / wsW
        val rawHf = rawRect.height.toFloat() / wsH

        // Trigger Full-Screen tier when the user pulls near or beyond the workspace edges.
        val best = if (rawWf >= 0.96f && rawHf >= 0.94f) {
            presets.last()
        } else {
            presets.filterNot { it.fullScreen }.minBy { p ->
                hypot((rawWf - p.wFraction).toDouble(), (rawHf - p.hFraction).toDouble())
            }
        }

        if (best.fullScreen) {
            return SnapResult(best, fullScreenBounds)
        }

        val targetW = (wsW * best.wFraction).roundToInt().coerceIn(minWidthPx, wsW)
        val targetH = (wsH * best.hFraction).roundToInt().coerceIn(minHeightPx, wsH)

        val top = rawRect.top.coerceIn(workspace.top, (workspace.bottom - targetH).coerceAtLeast(workspace.top))
        val left = if (anchorRight) {
            (rawRect.right - targetW).coerceIn(workspace.left, (workspace.right - targetW).coerceAtLeast(workspace.left))
        } else {
            rawRect.left.coerceIn(workspace.left, (workspace.right - targetW).coerceAtLeast(workspace.left))
        }

        return SnapResult(
            preset = best,
            rect = StageRect(left, top, left + targetW, top + targetH)
        )
    }
}

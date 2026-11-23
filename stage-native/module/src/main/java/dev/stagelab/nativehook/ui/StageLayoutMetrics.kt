package dev.stagelab.nativehook.ui

import android.graphics.Rect
import dev.stagelab.nativecore.WindowBounds
import kotlin.math.min

/**
 * Centralized, responsive layout metrics calculator for tablets of all sizes & DPIs
 * (8.8" Pad mini, 11" Pad, 12.1"/13.2" Pad Pro, landscape & portrait).
 *
 * Shared by both `system_server` (`SystemServerFlexibleHook`) and `com.android.systemui`
 * (`ColorOsNativeWindowBridge`, `StageMagicTransitionOverlay`, `AppSearchOverlayController`)
 * so every overlay and native window stays proportionally aligned with zero hardcoded screen sizes.
 */
object StageLayoutMetrics {

    data class Metrics(
        val screenW: Int,
        val screenH: Int,
        val density: Float,
        val isLandscape: Boolean,
        val backSafePx: Int,
        val stripCardWidthPx: Int,
        val stripCardHeightPx: Int,
        val stripAnchorX: Float,
        val sideGapPx: Int,
        val mainStageRect: Rect,
        val multiColGap2Px: Int,
        val multiColGap3Px: Int,
        val cornerRadiusPx: Float,
        val searchPanelWidthPx: Int,
        val searchScrollHeightPx: Int,
        val searchGridColumns: Int,
    ) {
        val mainStageBounds: WindowBounds
            get() = WindowBounds(mainStageRect.left, mainStageRect.top, mainStageRect.right, mainStageRect.bottom)
    }

    fun compute(
        screenW: Int,
        screenH: Int,
        density: Float = inferDensity(screenW, screenH),
        stripVisible: Boolean = true,
    ): Metrics {
        val safeW = screenW.coerceAtLeast(800)
        val safeH = screenH.coerceAtLeast(600)
        val safeDensity = density.coerceIn(1.25f, 4.0f)
        val isLandscape = safeW >= safeH
        val shortEdgeDp = min(safeW, safeH) / safeDensity

        // Left system Back-gesture safe zone (~28dp)
        val backSafePx = (28f * safeDensity).toInt()

        // Responsive slanted 3D Recent Strip card dimensions
        val stripCardWidthDp = (shortEdgeDp * 0.228f).coerceIn(124f, 172f)
        val stripCardHeightDp = stripCardWidthDp * 0.72f
        val stripCardWidthPx = (stripCardWidthDp * safeDensity).toInt()
        val stripCardHeightPx = (stripCardHeightDp * safeDensity).toInt()
        // Account for 28° 3D rotationY horizontal projection (~0.88) + padding
        val stripVisualRightPx = backSafePx + (stripCardWidthPx * 0.90f).toInt()

        // Responsive Dock reserved height + crisp 13dp gap above Dock (tighter, never overlapping)
        val dockIconDp = (shortEdgeDp * 0.075f).coerceIn(44f, 56f)
        val dockPlusGapDp = dockIconDp + (if (isLandscape) 64f else 56f)
        val bottomReservedPx = (dockPlusGapDp * safeDensity).toInt()

        // Symmetrical left/right margins (~19dp in landscape, ~14dp in portrait)
        val sideGapPx = ((if (isLandscape) 19f else 14f) * safeDensity).toInt()
        val topPx = ((if (isLandscape) 31f else 34f) * safeDensity).toInt()

        val leftPx = if (stripVisible) stripVisualRightPx + sideGapPx else sideGapPx
        val rightPx = (safeW - sideGapPx).coerceAtLeast(leftPx + (320f * safeDensity).toInt())
        val bottomPx = (safeH - bottomReservedPx).coerceAtLeast(topPx + (320f * safeDensity).toInt())

        val searchPanelWidthPx = (safeW * (if (isLandscape) 0.56f else 0.86f))
            .toInt()
            .coerceIn((360f * safeDensity).toInt(), (740f * safeDensity).toInt())
        val searchScrollHeightPx = if (isLandscape) {
            (safeH - bottomReservedPx - (165f * safeDensity).toInt())
                .coerceIn((180f * safeDensity).toInt(), (330f * safeDensity).toInt())
        } else {
            (safeH * 0.39f).toInt()
                .coerceIn((260f * safeDensity).toInt(), (450f * safeDensity).toInt())
        }
        val searchGridColumns = if (isLandscape) {
            (shortEdgeDp / 106f).toInt().coerceIn(5, 8)
        } else {
            4
        }

        return Metrics(
            screenW = safeW,
            screenH = safeH,
            density = safeDensity,
            isLandscape = isLandscape,
            backSafePx = backSafePx,
            stripCardWidthPx = stripCardWidthPx,
            stripCardHeightPx = stripCardHeightPx,
            stripAnchorX = backSafePx + (6f * safeDensity),
            sideGapPx = sideGapPx,
            mainStageRect = Rect(leftPx, topPx, rightPx, bottomPx),
            multiColGap2Px = (10f * safeDensity).toInt(),
            multiColGap3Px = (8f * safeDensity).toInt(),
            cornerRadiusPx = 26f * safeDensity,
            searchPanelWidthPx = searchPanelWidthPx,
            searchScrollHeightPx = searchScrollHeightPx,
            searchGridColumns = searchGridColumns,
        )
    }

    /**
     * Computes clean, non-overlapping tiled window rects inside [metrics.mainStageRect]:
     * - Landscape: equal side-by-side columns (wide tablet canvas).
     * - Portrait: full-width stacked rows so windows NEVER squish into narrow vertical slivers.
     */
    fun computeTiledRects(metrics: Metrics, count: Int): List<Rect> {
        val stage = metrics.mainStageRect
        val n = count.coerceIn(1, 3)
        if (n == 1) return listOf(Rect(stage))
        val gap = if (n == 2) metrics.multiColGap2Px else metrics.multiColGap3Px
        return if (metrics.isLandscape) {
            val colW = ((stage.width() - gap * (n - 1)) / n).coerceAtLeast((140f * metrics.density).toInt())
            List(n) { i ->
                val l = stage.left + i * (colW + gap)
                val r = if (i == n - 1) stage.right else l + colW
                Rect(l, stage.top, r, stage.bottom)
            }
        } else {
            val rowH = ((stage.height() - gap * (n - 1)) / n).coerceAtLeast((160f * metrics.density).toInt())
            List(n) { i ->
                val t = stage.top + i * (rowH + gap)
                val b = if (i == n - 1) stage.bottom else t + rowH
                Rect(stage.left, t, stage.right, b)
            }
        }
    }

    fun computeTiledBounds(metrics: Metrics, count: Int): List<WindowBounds> =
        computeTiledRects(metrics, count).map { WindowBounds(it.left, it.top, it.right, it.bottom) }

    /**
     * Fallback density estimation when called inside `system_server` without a DisplayMetrics instance.
     */
    fun inferDensity(screenW: Int, screenH: Int): Float {
        val shortPx = min(screenW, screenH)
        return when {
            shortPx >= 2000 -> 3.0f
            shortPx >= 1500 -> 2.625f
            shortPx >= 1100 -> 2.0f
            else -> 1.75f
        }
    }
}

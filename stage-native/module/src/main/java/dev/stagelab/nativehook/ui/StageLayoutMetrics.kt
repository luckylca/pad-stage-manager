package dev.stagelab.nativehook.ui

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import dev.stagelab.nativecore.WindowBounds
import kotlin.math.max
import kotlin.math.min

/**
 * Centralized, responsive layout metrics calculator for devices of all sizes, DPIs & insets:
 * - Compact / Foldables (`< 540dp` short edge)
 * - 8.8" Mini Tablets (`540–699dp`, e.g. OPPO Pad mini)
 * - 11"–12.1" Standard Tablets (`700–859dp`)
 * - 13.2"+ Pro Tablets (`860dp+`)
 *
 * Shared by `system_server` and `com.android.systemui` overlays so native app windows,
 * left strip cards, Dock, and App Search stay aligned with zero hardcoded device dimensions.
 */
object StageLayoutMetrics {

    enum class DeviceTier(val label: String) {
        COMPACT_FOLDABLE("Foldable/Compact"),
        MINI_TABLET("Mini Tablet"),
        STANDARD_TABLET("Standard Tablet"),
        LARGE_TABLET("Large Tablet"),
    }

    data class DisplaySnapshot(
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val safeLeftPx: Int = 0,
        val safeTopPx: Int = 0,
        val safeRightPx: Int = 0,
        val safeBottomPx: Int = 0,
    )

    data class Metrics(
        val screenW: Int,
        val screenH: Int,
        val density: Float,
        val isLandscape: Boolean,
        val deviceTier: DeviceTier,
        val safeLeftPx: Int,
        val safeTopPx: Int,
        val safeRightPx: Int,
        val extraNavBottomPx: Int,
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
        val maxPinnedDockCount: Int,
    ) {
        val mainStageBounds: WindowBounds
            get() = WindowBounds(mainStageRect.left, mainStageRect.top, mainStageRect.right, mainStageRect.bottom)
    }

    fun resolveDisplaySnapshot(context: Context): DisplaySnapshot {
        val dm = context.resources.displayMetrics
        val density = dm.density.coerceIn(1.0f, 4.5f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                val maxMetrics = runCatching { wm.maximumWindowMetrics }.getOrNull()
                if (maxMetrics != null) {
                    val b = maxMetrics.bounds
                    if (b.width() > 0 && b.height() > 0) {
                        val insets = runCatching {
                            maxMetrics.windowInsets.getInsetsIgnoringVisibility(
                                WindowInsets.Type.statusBars() or
                                    WindowInsets.Type.navigationBars() or
                                    WindowInsets.Type.displayCutout(),
                            )
                        }.getOrNull()
                        return DisplaySnapshot(
                            widthPx = b.width(),
                            heightPx = b.height(),
                            density = density,
                            safeLeftPx = insets?.left ?: 0,
                            safeTopPx = insets?.top ?: 0,
                            safeRightPx = insets?.right ?: 0,
                            safeBottomPx = insets?.bottom ?: 0,
                        )
                    }
                }
            }
        }
        return DisplaySnapshot(
            widthPx = dm.widthPixels.coerceAtLeast(800),
            heightPx = dm.heightPixels.coerceAtLeast(600),
            density = density,
        )
    }

    fun fromContext(context: Context, stripVisible: Boolean = true): Metrics {
        val snap = resolveDisplaySnapshot(context)
        return compute(
            screenW = snap.widthPx,
            screenH = snap.heightPx,
            density = snap.density,
            stripVisible = stripVisible,
            safeLeftPx = snap.safeLeftPx,
            safeTopPx = snap.safeTopPx,
            safeRightPx = snap.safeRightPx,
            safeBottomPx = snap.safeBottomPx,
        )
    }

    fun compute(
        screenW: Int,
        screenH: Int,
        density: Float = inferDensity(screenW, screenH),
        stripVisible: Boolean = true,
        safeLeftPx: Int = 0,
        safeTopPx: Int = 0,
        safeRightPx: Int = 0,
        safeBottomPx: Int = 0,
    ): Metrics {
        val safeW = screenW.coerceAtLeast(720)
        val safeH = screenH.coerceAtLeast(600)
        val safeDensity = density.coerceIn(1.0f, 4.5f)
        val isLandscape = safeW >= safeH
        val shortEdgeDp = min(safeW, safeH) / safeDensity
        val deviceTier = when {
            shortEdgeDp < 540f -> DeviceTier.COMPACT_FOLDABLE
            shortEdgeDp < 700f -> DeviceTier.MINI_TABLET
            shortEdgeDp < 860f -> DeviceTier.STANDARD_TABLET
            else -> DeviceTier.LARGE_TABLET
        }

        // Only expand bottom clearance when a persistent 3-button nav bar (>24dp) is present
        val extraNavBottomPx = (safeBottomPx - (24f * safeDensity).toInt()).coerceAtLeast(0)

        // Left system Back-gesture & cutout safe zone
        val baseBackSafePx = ((if (deviceTier == DeviceTier.COMPACT_FOLDABLE) 18f else 28f) * safeDensity).toInt()
        val backSafePx = max(baseBackSafePx, safeLeftPx + (6f * safeDensity).toInt())

        // Responsive slanted 3D Recent Strip card dimensions across foldables / mini / pro tablets
        val stripRatio = if (deviceTier == DeviceTier.COMPACT_FOLDABLE) 0.19f else 0.228f
        val minStripDp = if (deviceTier == DeviceTier.COMPACT_FOLDABLE) 88f else 124f
        val stripCardWidthDp = (shortEdgeDp * stripRatio).coerceIn(minStripDp, 184f)
        val stripCardHeightDp = stripCardWidthDp * 0.72f
        val stripCardWidthPx = (stripCardWidthDp * safeDensity).toInt()
        val stripCardHeightPx = (stripCardHeightDp * safeDensity).toInt()
        val stripVisualRightPx = backSafePx + (stripCardWidthPx * 0.90f).toInt()

        // Responsive Dock reserved height + crisp gap above Dock + nav bar clearance if any
        val dockIconDp = (shortEdgeDp * 0.075f).coerceIn(42f, 56f)
        val dockPlusGapDp = dockIconDp + (if (isLandscape) 64f else 56f)
        val bottomReservedPx = (dockPlusGapDp * safeDensity).toInt() + extraNavBottomPx

        // Symmetrical left/right margins + cutout awareness
        val sideGapPx = ((if (isLandscape) 19f else 14f) * safeDensity).toInt()
        val defaultTopPx = ((if (isLandscape) 31f else 34f) * safeDensity).toInt()
        val topPx = max(defaultTopPx, safeTopPx + (4f * safeDensity).toInt())

        val minNonStripLeft = max(sideGapPx, safeLeftPx + (10f * safeDensity).toInt())
        val leftPx = if (stripVisible) stripVisualRightPx + sideGapPx else minNonStripLeft
        val rightEdgeMargin = max(sideGapPx, safeRightPx + (10f * safeDensity).toInt())
        val rightPx = (safeW - rightEdgeMargin).coerceAtLeast(leftPx + (280f * safeDensity).toInt())
        val bottomPx = (safeH - bottomReservedPx).coerceAtLeast(topPx + (280f * safeDensity).toInt())

        val widthPct = when {
            isLandscape && deviceTier == DeviceTier.LARGE_TABLET -> 0.50f
            isLandscape -> 0.56f
            deviceTier == DeviceTier.COMPACT_FOLDABLE -> 0.92f
            else -> 0.86f
        }
        val searchPanelWidthPx = (safeW * widthPct)
            .toInt()
            .coerceIn((320f * safeDensity).toInt(), (780f * safeDensity).toInt())
        val searchScrollHeightPx = if (isLandscape) {
            (safeH - bottomReservedPx - (165f * safeDensity).toInt())
                .coerceIn((180f * safeDensity).toInt(), (350f * safeDensity).toInt())
        } else {
            (safeH * 0.39f).toInt()
                .coerceIn((240f * safeDensity).toInt(), (480f * safeDensity).toInt())
        }
        val searchGridColumns = when {
            isLandscape -> (shortEdgeDp / 106f).toInt().coerceIn(5, 8)
            deviceTier == DeviceTier.COMPACT_FOLDABLE -> 3
            deviceTier == DeviceTier.STANDARD_TABLET || deviceTier == DeviceTier.LARGE_TABLET -> 5
            else -> 4
        }
        val maxPinnedDockCount = when {
            !isLandscape && deviceTier == DeviceTier.COMPACT_FOLDABLE -> 5
            !isLandscape -> 7
            deviceTier == DeviceTier.LARGE_TABLET -> 12
            else -> 10
        }

        return Metrics(
            screenW = safeW,
            screenH = safeH,
            density = safeDensity,
            isLandscape = isLandscape,
            deviceTier = deviceTier,
            safeLeftPx = safeLeftPx,
            safeTopPx = safeTopPx,
            safeRightPx = safeRightPx,
            extraNavBottomPx = extraNavBottomPx,
            backSafePx = backSafePx,
            stripCardWidthPx = stripCardWidthPx,
            stripCardHeightPx = stripCardHeightPx,
            stripAnchorX = backSafePx + (6f * safeDensity),
            sideGapPx = sideGapPx,
            mainStageRect = Rect(leftPx, topPx, rightPx, bottomPx),
            multiColGap2Px = ((if (deviceTier == DeviceTier.LARGE_TABLET) 12f else 10f) * safeDensity).toInt(),
            multiColGap3Px = ((if (deviceTier == DeviceTier.LARGE_TABLET) 10f else 8f) * safeDensity).toInt(),
            cornerRadiusPx = 26f * safeDensity,
            searchPanelWidthPx = searchPanelWidthPx,
            searchScrollHeightPx = searchScrollHeightPx,
            searchGridColumns = searchGridColumns,
            maxPinnedDockCount = maxPinnedDockCount,
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

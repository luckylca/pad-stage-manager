package dev.stagelab.android.stage

import dev.stagelab.android.core.stage.StageRect
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Eased real-task bounds animator (P8).
 *
 * Drives the actual Android FREEFORM Task bounds and Stage chrome overlays in lockstep using a
 * short cubic-out curve (~160 ms, 6 eased steps). This keeps ADR-004 intact (100% native touch,
 * IME, Back and accessibility) while giving real windows visible glide transitions during group
 * switches, minimize/restore, Dock launch, and window arrangement presets.
 */
object StageWindowAnimator {

    const val DEFAULT_STEPS = 6
    const val DEFAULT_DURATION_MS = 156L

    fun easeOutCubic(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    fun lerpRect(from: StageRect, to: StageRect, t: Float): StageRect {
        val e = easeOutCubic(t)
        return StageRect(
            left = (from.left + (to.left - from.left) * e).roundToInt(),
            top = (from.top + (to.top - from.top) * e).roundToInt(),
            right = (from.right + (to.right - from.right) * e).roundToInt(),
            bottom = (from.bottom + (to.bottom - from.bottom) * e).roundToInt()
        )
    }

    /**
     * Runs an eased keyframe transition from [from] to [to], invoking [onStep] at each frame.
     * Skips animation when the delta is negligible.
     */
    suspend fun animate(
        from: StageRect,
        to: StageRect,
        steps: Int = DEFAULT_STEPS,
        durationMs: Long = DEFAULT_DURATION_MS,
        onStep: suspend (StageRect) -> Unit
    ) {
        val maxDelta = maxOf(
            abs(to.left - from.left),
            abs(to.top - from.top),
            abs(to.right - from.right),
            abs(to.bottom - from.bottom)
        )
        if (maxDelta < 16 || steps <= 1) {
            onStep(to)
            return
        }
        val frameDelay = (durationMs / steps).coerceAtLeast(16L)
        for (i in 1..steps) {
            val t = i.toFloat() / steps.toFloat()
            val rect = if (i == steps) to else lerpRect(from, to, t)
            onStep(rect)
            if (i < steps) delay(frameDelay)
        }
    }
}

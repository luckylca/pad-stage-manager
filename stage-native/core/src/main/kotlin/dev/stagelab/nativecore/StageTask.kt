package dev.stagelab.nativecore

/**
 * Pure value type for window bounds in display pixel coordinates.
 * Kept Android-free so `:core` runs fast deterministic JVM unit tests.
 */
data class WindowBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width == 0 || height == 0

    companion object {
        val EMPTY = WindowBounds(0, 0, 0, 0)
    }
}

/**
 * Logical metadata for a real Android/ColorOS Task managed by ActivityTaskManager / WMS.
 * This is NEVER an independent window container; ATM/WMS remains the source of truth.
 */
data class StageTask(
    val taskId: Int,
    val packageName: String,
    val componentName: String = "",
    val bounds: WindowBounds = WindowBounds.EMPTY,
    val windowingMode: Int = WINDOWING_MODE_COLOROS_FLEXIBLE,
    val zOrder: Int = 0,
    val joinedAtMs: Long = 0L,
    val lastFocusedAtMs: Long = 0L,
    val isRunning: Boolean = true,
    val isVisible: Boolean = true,
    /** True when opened as a Smart Sidebar / corner mini floating window belonging to this Stage. */
    val isMiniFloating: Boolean = false,
) {
    companion object {
        const val WINDOWING_MODE_UNDEFINED = 0
        const val WINDOWING_MODE_FULLSCREEN = 1
        const val WINDOWING_MODE_FREEFORM = 5
        const val WINDOWING_MODE_MULTI_WINDOW = 6
        /** ColorOS 17 Flexible Window mode verified on OPPO Pad mini (OPD2515). */
        const val WINDOWING_MODE_COLOROS_FLEXIBLE = 100
    }
}

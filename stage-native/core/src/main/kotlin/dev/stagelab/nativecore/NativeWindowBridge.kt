package dev.stagelab.nativecore

/**
 * Bridge between the pure logical Stage state machine and Android / ColorOS native window management.
 * Implementations MUST delegate actual window lifecycle, bounds, focus, and animations to native
 * ActivityTaskManager / WindowManager / ColorOS FlexibleWindow mechanisms.
 */
interface NativeWindowBridge {
    /**
     * Launch a package into ColorOS native Flexible Window mode when it does not yet belong to any Stage.
     * Returns the resulting taskId if immediately known, or null if reported asynchronously via task callbacks.
     */
    fun launchNativeWindow(packageName: String, preferredBounds: WindowBounds? = null): Int?

    /**
     * Activate an existing live Stage by restoring its tasks' saved bounds and Z-order without restarting them,
     * and parking the previously active Stage's tasks into background while keeping them alive.
     */
    fun switchNativeStage(
        tasksToShow: List<StageTask>,
        tasksToPark: List<StageTask>,
    )

    /**
     * Park specific live tasks into background (e.g., on S4 eviction or S5 minimize/split) without killing them.
     */
    fun parkNativeTasks(tasks: List<StageTask>)

    /**
     * Bring an existing live task into the active stage with optional bounds restoration.
     */
    fun bringTaskToActiveStage(task: StageTask)

    /**
     * Update a live native window's bounds/z-order.
     */
    fun updateTaskBounds(taskId: Int, bounds: WindowBounds)

    /**
     * Show the native Launcher desktop when Active Stage becomes empty (e.g., S5 single-window minimize).
     */
    fun showNativeDesktop()

    /**
     * Request native closure of a task.
     */
    fun closeNativeTask(taskId: Int)
}

/**
 * Safe read-only / no-op implementation used during N2 observation and headless unit tests.
 */
open class NoOpNativeWindowBridge : NativeWindowBridge {
    override fun launchNativeWindow(packageName: String, preferredBounds: WindowBounds?): Int? = null
    override fun switchNativeStage(tasksToShow: List<StageTask>, tasksToPark: List<StageTask>) = Unit
    override fun parkNativeTasks(tasks: List<StageTask>) = Unit
    override fun bringTaskToActiveStage(task: StageTask) = Unit
    override fun updateTaskBounds(taskId: Int, bounds: WindowBounds) = Unit
    override fun showNativeDesktop() = Unit
    override fun closeNativeTask(taskId: Int) = Unit
}

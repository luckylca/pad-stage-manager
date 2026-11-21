package dev.stagelab.nativecore

/**
 * Controller bridging native ColorOS Dock / Taskbar and native window `···` menu actions
 * into the Stage state machine.
 */
class DockIntegrationController(
    private val transitionController: StageTransitionController,
    private val bridge: NativeWindowBridge = NoOpNativeWindowBridge(),
) {
    var isDockHiddenBySetting: Boolean = false
        private set

    var isDockTemporarilyRevealed: Boolean = false
        private set

    val isDockVisible: Boolean
        get() = !isDockHiddenBySetting || isDockTemporarilyRevealed

    fun setDockHiddenBySetting(hidden: Boolean) {
        isDockHiddenBySetting = hidden
        if (!hidden) {
            isDockTemporarilyRevealed = false
        }
    }

    fun onBottomSwipeReveal() {
        if (isDockHiddenBySetting) {
            isDockTemporarilyRevealed = true
        }
    }

    fun dismissTemporaryReveal() {
        isDockTemporarilyRevealed = false
    }

    /** Tap an app icon on Dock -> S1 create new Stage (or activate existing Stage if already open). */
    fun onDockAppClicked(packageName: String, preferredBounds: WindowBounds? = null): StageSession {
        isDockTemporarilyRevealed = false
        return transitionController.onAppLaunchedFromLauncherOrDock(packageName, preferredBounds)
    }

    /** Long-press drag an app icon from Dock into center -> S3 merge into current Active Stage. */
    fun onDockAppDraggedToCenter(packageName: String, dropBounds: WindowBounds? = null): StageSession {
        isDockTemporarilyRevealed = false
        val existingStage = transitionController.registry.findStageByPackage(packageName)
        val existingTask = existingStage?.tasks?.firstOrNull { it.packageName == packageName && it.isRunning }
        if (existingTask != null) {
            val updated = if (dropBounds != null) existingTask.copy(bounds = dropBounds) else existingTask
            return transitionController.mergeTaskIntoActiveStage(updated)
        }
        val launchedTaskId = bridge.launchNativeWindow(packageName, dropBounds)
        if (launchedTaskId != null) {
            val now = transitionController.registry.nowMs()
            val task = StageTask(
                taskId = launchedTaskId,
                packageName = packageName,
                bounds = dropBounds ?: WindowBounds.EMPTY,
                windowingMode = StageTask.WINDOWING_MODE_COLOROS_FLEXIBLE,
                joinedAtMs = now,
                lastFocusedAtMs = now,
                isRunning = true,
                isVisible = true,
            )
            return transitionController.mergeTaskIntoActiveStage(task)
        }
        return transitionController.registry.snapshot()
    }

    /** Native window `···` menu -> Add Another Window -> select app (S3). */
    fun onWindowMenuAddWindow(packageName: String, preferredBounds: WindowBounds? = null): StageSession =
        onDockAppDraggedToCenter(packageName, preferredBounds)

    /** Native window `···` menu -> Minimize (S5). */
    fun onWindowMenuMinimize(taskId: Int): StageSession =
        transitionController.minimizeOrSplitTask(taskId)

    /** Native window `···` menu -> Close (S6). */
    fun onWindowMenuClose(taskId: Int): StageSession =
        transitionController.closeTask(taskId)
}

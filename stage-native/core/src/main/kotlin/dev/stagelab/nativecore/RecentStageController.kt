package dev.stagelab.nativecore

/**
 * Controller for the left Recent Strip (iPad Stage Manager style).
 * - Displays at most 4 recent Stages without destroying older history.
 * - Supports click-to-switch (S2), drag-to-center merge (S3), auto-hide and edge-swipe reveal.
 */
class RecentStageController(
    private val transitionController: StageTransitionController,
) {
    var isHiddenByUserSetting: Boolean = false
        private set

    var isTemporarilyRevealed: Boolean = false
        private set

    val isStripVisible: Boolean
        get() = (!isHiddenByUserSetting || isTemporarilyRevealed) && visibleRecentStages().isNotEmpty()

    fun visibleRecentStages(): List<Stage> = transitionController.registry.snapshot().visibleRecentStages

    fun allRecentStages(): List<Stage> = transitionController.registry.snapshot().allRecentStages

    fun setHiddenByUserSetting(hidden: Boolean) {
        isHiddenByUserSetting = hidden
        if (!hidden) {
            isTemporarilyRevealed = false
        }
    }

    fun onLeftEdgeSwipeReveal() {
        if (isHiddenByUserSetting) {
            isTemporarilyRevealed = true
        }
    }

    fun dismissTemporaryReveal() {
        isTemporarilyRevealed = false
    }

    /** Click a Stage thumbnail on the Recent Strip -> S2 switch. */
    fun onStageThumbnailClicked(stageId: String): StageSession {
        isTemporarilyRevealed = false
        return transitionController.switchToStage(stageId)
    }

    /** Drag a specific task (or top task of a recent Stage) into the center Active Stage -> S3 merge. */
    fun onStageTaskDraggedToCenter(stageId: String, taskId: Int? = null): StageSession {
        isTemporarilyRevealed = false
        val stage = transitionController.registry.getStage(stageId) ?: return transitionController.registry.snapshot()
        val chosenTaskId = taskId ?: stage.topTask?.taskId ?: return transitionController.registry.snapshot()
        return transitionController.mergeExistingTaskIntoActiveStage(chosenTaskId)
    }

    /** Four-finger horizontal swipe to cycle between Active Stage and Recent Strip stages. */
    fun onFourFingerSwipeNext(): StageSession {
        val next = visibleRecentStages().firstOrNull() ?: return transitionController.registry.snapshot()
        return transitionController.switchToStage(next.stageId)
    }
}

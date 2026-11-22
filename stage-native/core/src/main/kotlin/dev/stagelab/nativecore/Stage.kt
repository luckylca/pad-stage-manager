package dev.stagelab.nativecore

/**
 * A dynamically generated group of 1..4 real system tasks.
 * Stages are never manually created or deleted by the user; they emerge on launch/merge/split
 * and vanish automatically when their last real task closes.
 */
data class Stage(
    val stageId: String,
    val tasks: List<StageTask>,
    val lastActiveTimeMs: Long,
    val isActive: Boolean,
) {
    init {
        require(stageId.isNotBlank()) { "stageId must not be blank" }
        require(tasks.size <= MAX_TASKS_PER_STAGE) {
            "Stage $stageId exceeded max capacity $MAX_TASKS_PER_STAGE (actual=${tasks.size})"
        }
        val uniqueIds = tasks.map { it.taskId }.toSet()
        require(uniqueIds.size == tasks.size) {
            "Stage $stageId contains duplicate taskIds: ${tasks.map { it.taskId }}"
        }
    }

    val taskIds: List<Int> get() = tasks.map { it.taskId }

    /** Ordered from bottom-most (lowest zOrder) to top-most (highest zOrder). */
    val tasksByZOrder: List<StageTask> get() = tasks.sortedWith(compareBy<StageTask> { it.zOrder }.thenBy { it.joinedAtMs })

    val topTask: StageTask? get() = tasksByZOrder.lastOrNull()

    fun containsTask(taskId: Int): Boolean = tasks.any { it.taskId == taskId }

    fun findTask(taskId: Int): StageTask? = tasks.firstOrNull { it.taskId == taskId }

    companion object {
        /** Effectively unlimited windows per Stage workspace (regular + mini floating windows). */
        const val MAX_TASKS_PER_STAGE = Int.MAX_VALUE
    }
}

package dev.stagelab.nativecore

import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory authoritative registry of dynamic Stages and Task->Stage ownership.
 * Enforces core invariants:
 * - Every taskId belongs to at most ONE Stage at any moment.
 * - At most ONE Stage is active at any moment.
 * - Empty Stages are automatically destroyed and never retained.
 * - Recent history is ordered most-recently-active first without destroying stages past index 4.
 */
class StageRegistry(
    val evictionPolicy: EvictionPolicy = EvictionPolicy.LOWEST_Z_ORDER_THEN_EARLIEST,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val stageSeq = AtomicLong(0L)
    private val stages = LinkedHashMap<String, Stage>()
    private val taskToStageId = HashMap<Int, String>()
    private val recentOrder = ArrayList<String>()
    private var activeStageId: String? = null

    fun nextStageId(): String = "stage-${stageSeq.incrementAndGet()}"

    fun nowMs(): Long = clock()

    fun snapshot(): StageSession = StageSession(
        activeStageId = activeStageId,
        stages = stages.toMap(),
        recentStageIds = recentOrder.toList(),
        evictionPolicy = evictionPolicy,
    )

    fun getStage(stageId: String): Stage? = stages[stageId]

    fun getActiveStage(): Stage? = activeStageId?.let { stages[it] }

    fun findStageIdForTask(taskId: Int): String? = taskToStageId[taskId]

    fun findStageForTask(taskId: Int): Stage? = taskToStageId[taskId]?.let { stages[it] }

    fun findStageByPackage(packageName: String): Stage? {
        val active = getActiveStage()
        if (active != null && active.tasks.any { it.packageName == packageName && it.isRunning }) {
            return active
        }
        for (id in recentOrder) {
            val st = stages[id] ?: continue
            if (st.tasks.any { it.packageName == packageName && it.isRunning }) {
                return st
            }
        }
        return stages.values.firstOrNull { st -> st.tasks.any { it.packageName == packageName && it.isRunning } }
    }

    fun putStage(stage: Stage) {
        if (stage.tasks.isEmpty()) {
            removeStage(stage.stageId)
            return
        }
        val previous = stages[stage.stageId]
        previous?.tasks?.forEach { oldTask ->
            if (taskToStageId[oldTask.taskId] == stage.stageId) {
                taskToStageId.remove(oldTask.taskId)
            }
        }
        // Guarantee single-ownership invariant across all stages.
        stage.tasks.forEach { task ->
            val oldOwner = taskToStageId[task.taskId]
            if (oldOwner != null && oldOwner != stage.stageId) {
                detachTaskInternal(oldOwner, task.taskId)
            }
            taskToStageId[task.taskId] = stage.stageId
        }
        stages[stage.stageId] = stage
        if (stage.isActive) {
            val prevActive = activeStageId
            if (prevActive != null && prevActive != stage.stageId) {
                deactivateStageToRecentTop(prevActive)
            }
            activeStageId = stage.stageId
            recentOrder.remove(stage.stageId)
        } else {
            if (activeStageId == stage.stageId) {
                activeStageId = null
            }
            if (!recentOrder.contains(stage.stageId)) {
                recentOrder.add(0, stage.stageId)
            }
        }
    }

    fun pushToRecentTop(stageId: String) {
        if (!stages.containsKey(stageId)) return
        if (activeStageId == stageId) {
            deactivateStageToRecentTop(stageId)
            return
        }
        recentOrder.remove(stageId)
        recentOrder.add(0, stageId)
    }

    fun deactivateStageToRecentTop(stageId: String) {
        val existing = stages[stageId] ?: return
        val updated = existing.copy(isActive = false, lastActiveTimeMs = nowMs())
        stages[stageId] = updated
        if (activeStageId == stageId) {
            activeStageId = null
        }
        recentOrder.remove(stageId)
        recentOrder.add(0, stageId)
    }

    fun setActiveStage(stageId: String?) {
        val current = activeStageId
        if (current == stageId) return
        if (current != null) {
            deactivateStageToRecentTop(current)
        }
        if (stageId == null) {
            activeStageId = null
            return
        }
        val target = stages[stageId] ?: return
        recentOrder.remove(stageId)
        activeStageId = stageId
        stages[stageId] = target.copy(isActive = true, lastActiveTimeMs = nowMs())
    }

    fun removeStage(stageId: String) {
        val removed = stages.remove(stageId)
        removed?.tasks?.forEach { task ->
            if (taskToStageId[task.taskId] == stageId) {
                taskToStageId.remove(task.taskId)
            }
        }
        recentOrder.remove(stageId)
        if (activeStageId == stageId) {
            activeStageId = null
        }
    }

    fun detachTask(taskId: Int): Stage? {
        val ownerId = taskToStageId[taskId] ?: return null
        return detachTaskInternal(ownerId, taskId)
    }

    private fun detachTaskInternal(stageId: String, taskId: Int): Stage? {
        taskToStageId.remove(taskId)
        val stage = stages[stageId] ?: return null
        val remaining = stage.tasks.filterNot { it.taskId == taskId }
        if (remaining.isEmpty()) {
            removeStage(stageId)
            return null
        }
        val updated = stage.copy(tasks = remaining)
        stages[stageId] = updated
        return updated
    }

    fun clear() {
        stages.clear()
        taskToStageId.clear()
        recentOrder.clear()
        activeStageId = null
    }

    /**
     * Reconstruct registry state after a SystemUI / module restart using the live tasks reported by ATM/WMS.
     * Visible flexible tasks form the Active Stage (capped at 4; overflow becomes recent single-window stages).
     * Background running tasks form recent single-window stages.
     */
    fun restoreFromLiveTasks(liveTasks: List<StageTask>) {
        clear()
        val running = liveTasks.filter { it.isRunning }
        if (running.isEmpty()) return

        val visible = running.filter { it.isVisible }
            .sortedWith(compareBy<StageTask> { it.zOrder }.thenBy { it.joinedAtMs }.thenBy { it.taskId })
        val background = running.filterNot { it.isVisible }
            .sortedByDescending { it.lastFocusedAtMs }

        if (visible.isNotEmpty()) {
            val activeTasks = visible.takeLast(Stage.MAX_TASKS_PER_STAGE)
            val overflowTasks = visible.dropLast(Stage.MAX_TASKS_PER_STAGE)
            val activeId = nextStageId()
            putStage(
                Stage(
                    stageId = activeId,
                    tasks = activeTasks,
                    lastActiveTimeMs = nowMs(),
                    isActive = true,
                ),
            )
            overflowTasks.forEach { task ->
                val overflowId = nextStageId()
                putStage(
                    Stage(
                        stageId = overflowId,
                        tasks = listOf(task.copy(isVisible = false)),
                        lastActiveTimeMs = task.lastFocusedAtMs,
                        isActive = false,
                    ),
                )
            }
        }

        background.forEach { task ->
            val bgId = nextStageId()
            putStage(
                Stage(
                    stageId = bgId,
                    tasks = listOf(task),
                    lastActiveTimeMs = task.lastFocusedAtMs,
                    isActive = false,
                ),
            )
        }
    }
}

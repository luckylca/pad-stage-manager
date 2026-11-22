package dev.stagelab.nativecore

/**
 * Pure state-machine controller enforcing transitions S1 through S6 and syncing with [NativeWindowBridge].
 *
 * - S1: Launch new app -> auto-create new Active Stage (previous Active Stage moves to Recent Strip top).
 *       If app already belongs to an existing Stage -> activate that Stage without duplicating tasks.
 * - S2: Switch Stage -> save outgoing bounds & Z-order, park outgoing tasks alive, restore incoming tasks with identical taskIds.
 * - S3: Merge task into current Active Stage -> detach from source Stage (auto-destroying empty source Stage).
 * - S4: Overflow past 4 windows -> deterministically evict 1 task into a new single-window Stage at Recent Strip top.
 * - S5: Minimize / split task -> detach into a new single-window Stage at Recent Strip top (kept alive);
 *       if Active Stage becomes empty -> show native Launcher desktop.
 * - S6: Auto-destroy Stage -> when last real Task closes/dies/is removed from Recents, delete Stage cleanly.
 */
class StageTransitionController(
    val registry: StageRegistry,
    private val bridge: NativeWindowBridge = NoOpNativeWindowBridge(),
) {
    private var zOrderCounter: Int = 0

    private fun nextZOrder(): Int = ++zOrderCounter

    /**
     * S1: User launches [packageName] from native Launcher or Dock.
     * - If an existing live Stage already contains [packageName], activates that Stage instead of duplicating.
     * - Otherwise launches natively and creates a new single-window Active Stage.
     */
    fun onAppLaunchedFromLauncherOrDock(
        packageName: String,
        preferredBounds: WindowBounds? = null,
    ): StageSession {
        val existingStage = registry.findStageByPackage(packageName)
        if (existingStage != null) {
            return switchToStage(existingStage.stageId)
        }
        val launchedTaskId = bridge.launchNativeWindow(packageName, preferredBounds)
        if (launchedTaskId != null) {
            val now = registry.nowMs()
            val task = StageTask(
                taskId = launchedTaskId,
                packageName = packageName,
                bounds = preferredBounds ?: WindowBounds.EMPTY,
                windowingMode = StageTask.WINDOWING_MODE_COLOROS_FLEXIBLE,
                zOrder = nextZOrder(),
                joinedAtMs = now,
                lastFocusedAtMs = now,
                isRunning = true,
                isVisible = true,
            )
            onNativeTaskAppeared(task, mergeIntoActive = false)
        }
        return registry.snapshot()
    }

    /**
     * Native callback: a real Task appeared in ATM/WMS.
     * Idempotent for duplicate callbacks with the same taskId.
     */
    fun onNativeTaskAppeared(
        task: StageTask,
        mergeIntoActive: Boolean = false,
    ): StageSession {
        val existingStageId = registry.findStageIdForTask(task.taskId)
        if (existingStageId != null) {
            // Duplicate callback: update metadata idempotently without duplicating Stage or Task.
            return onNativeTaskInfoChanged(task)
        }

        if (mergeIntoActive && registry.getActiveStage() != null) {
            return mergeTaskIntoActiveStage(task)
        }

        // S1: Create new Stage automatically and move previous Active Stage to Recent Strip top.
        val previousActive = registry.getActiveStage()
        val now = registry.nowMs()
        val normalized = task.copy(
            zOrder = if (task.zOrder > 0) task.zOrder else nextZOrder(),
            joinedAtMs = if (task.joinedAtMs > 0L) task.joinedAtMs else now,
            lastFocusedAtMs = if (task.lastFocusedAtMs > 0L) task.lastFocusedAtMs else now,
            isRunning = true,
            isVisible = true,
        )
        val newStageId = registry.nextStageId()
        registry.putStage(
            Stage(
                stageId = newStageId,
                tasks = listOf(normalized),
                lastActiveTimeMs = now,
                isActive = true,
            ),
        )
        if (previousActive != null) {
            bridge.parkNativeTasks(previousActive.tasks)
        }
        return registry.snapshot()
    }

    /**
     * Native callback: task bounds, focus, visibility, or windowing mode changed.
     * Idempotent if the task is already up-to-date; ignores unknown tasks safely.
     */
    fun onNativeTaskInfoChanged(updatedTask: StageTask): StageSession {
        val stageId = registry.findStageIdForTask(updatedTask.taskId) ?: return registry.snapshot()
        val stage = registry.getStage(stageId) ?: return registry.snapshot()
        val existing = stage.findTask(updatedTask.taskId) ?: return registry.snapshot()

        val merged = existing.copy(
            packageName = updatedTask.packageName.ifBlank { existing.packageName },
            componentName = updatedTask.componentName.ifBlank { existing.componentName },
            bounds = if (!updatedTask.bounds.isEmpty) updatedTask.bounds else existing.bounds,
            windowingMode = if (updatedTask.windowingMode != StageTask.WINDOWING_MODE_UNDEFINED) {
                updatedTask.windowingMode
            } else {
                existing.windowingMode
            },
            zOrder = if (updatedTask.zOrder > 0) updatedTask.zOrder else existing.zOrder,
            lastFocusedAtMs = maxOf(existing.lastFocusedAtMs, updatedTask.lastFocusedAtMs),
            isRunning = updatedTask.isRunning,
            isVisible = updatedTask.isVisible,
            isMiniFloating = existing.isMiniFloating || updatedTask.isMiniFloating,
        )

        if (merged == existing) {
            return registry.snapshot()
        }

        val updatedTasks = stage.tasks.map { if (it.taskId == merged.taskId) merged else it }
        registry.putStage(stage.copy(tasks = updatedTasks))
        return registry.snapshot()
    }

    /**
     * Native callback: user focused a task within the active Stage (raises Z-order).
     */
    fun onNativeTaskFocused(taskId: Int): StageSession {
        val stageId = registry.findStageIdForTask(taskId) ?: return registry.snapshot()
        val stage = registry.getStage(stageId) ?: return registry.snapshot()
        val now = registry.nowMs()
        val newZ = nextZOrder()
        val updatedTasks = stage.tasks.map {
            if (it.taskId == taskId) {
                it.copy(zOrder = newZ, lastFocusedAtMs = now, isVisible = true)
            } else {
                it
            }
        }
        registry.putStage(stage.copy(tasks = updatedTasks, lastActiveTimeMs = now))
        return registry.snapshot()
    }

    /**
     * S2: Switch Active Stage to [targetStageId].
     * Saves outgoing bounds/Z-order, moves outgoing Stage to Recent Strip top,
     * restores incoming Stage's tasks with identical taskIds via [NativeWindowBridge].
     */
    fun switchToStage(targetStageId: String): StageSession {
        val target = registry.getStage(targetStageId) ?: return registry.snapshot()
        val outgoing = registry.getActiveStage()
        if (outgoing?.stageId == targetStageId) {
            return registry.snapshot()
        }

        val parkedTasks = outgoing?.tasks?.map { it.copy(isVisible = false) } ?: emptyList()
        if (outgoing != null) {
            registry.putStage(
                outgoing.copy(
                    tasks = parkedTasks,
                    isActive = false,
                    lastActiveTimeMs = registry.nowMs(),
                ),
            )
            registry.pushToRecentTop(outgoing.stageId)
        }

        val restoredTasks = target.tasksByZOrder.map { it.copy(isVisible = true) }
        registry.putStage(
            target.copy(
                tasks = restoredTasks,
                isActive = true,
                lastActiveTimeMs = registry.nowMs(),
            ),
        )
        bridge.switchNativeStage(
            tasksToShow = restoredTasks,
            tasksToPark = parkedTasks,
        )
        return registry.snapshot()
    }

    /**
     * S3 & S4: Merge an existing task (from Recent Strip, Dock, or window menu) into current Active Stage.
     * - If Active Stage is empty, creates a new Active Stage with this task.
     * - If task belonged to another Stage, removes it from source Stage (auto-destroying empty source Stage).
     * - If Active Stage already has 4 windows, applies deterministic S4 eviction into a new single-window Stage
     *   placed at the top of Recent Strip (never closing the evicted task).
     */
    fun mergeTaskIntoActiveStage(task: StageTask): StageSession {
        val active = registry.getActiveStage()
        if (active == null) {
            return onNativeTaskAppeared(task, mergeIntoActive = false)
        }
        if (active.containsTask(task.taskId)) {
            return onNativeTaskFocused(task.taskId)
        }

        // Remove from source stage if present (auto-destroys source if it becomes empty).
        registry.detachTask(task.taskId)

        val now = registry.nowMs()
        val incoming = task.copy(
            zOrder = nextZOrder(),
            joinedAtMs = now,
            lastFocusedAtMs = now,
            isRunning = true,
            isVisible = true,
        )

        val currentActive = registry.getActiveStage() ?: active
        val combined = currentActive.tasks.toMutableList()
        var evictedTask: StageTask? = null

        if (combined.size >= Stage.MAX_TASKS_PER_STAGE) {
            // S4: Deterministic eviction
            evictedTask = selectEvictionCandidate(combined, registry.evictionPolicy)
            combined.remove(evictedTask)
        }
        combined.add(incoming)

        registry.putStage(
            currentActive.copy(
                tasks = combined,
                lastActiveTimeMs = now,
                isActive = true,
            ),
        )

        if (evictedTask != null) {
            val parked = evictedTask.copy(isVisible = false)
            val evictedStageId = registry.nextStageId()
            registry.putStage(
                Stage(
                    stageId = evictedStageId,
                    tasks = listOf(parked),
                    lastActiveTimeMs = now,
                    isActive = false,
                ),
            )
            registry.pushToRecentTop(evictedStageId)
            bridge.parkNativeTasks(listOf(parked))
        }

        bridge.bringTaskToActiveStage(incoming)
        return registry.snapshot()
    }

    /**
     * S3 helper: Merge a task by [taskId] from another Stage into the current Active Stage.
     */
    fun mergeExistingTaskIntoActiveStage(taskId: Int): StageSession {
        val sourceStage = registry.findStageForTask(taskId) ?: return registry.snapshot()
        val task = sourceStage.findTask(taskId) ?: return registry.snapshot()
        return mergeTaskIntoActiveStage(task)
    }

    /**
     * S5: Minimize / split [taskId] out of its Stage.
     * - Multi-window Stage: removes task from Stage, forms a new single-window Stage at Recent Strip top, keeps task alive.
     * - Single-window Active Stage: parks task into Recent Strip top, Active Stage becomes null, shows native Launcher desktop.
     */
    fun minimizeOrSplitTask(taskId: Int): StageSession {
        val owner = registry.findStageForTask(taskId) ?: return registry.snapshot()
        val task = owner.findTask(taskId) ?: return registry.snapshot()
        val wasActive = owner.isActive
        val now = registry.nowMs()

        if (owner.tasks.size == 1) {
            // Single-window stage: keep as a recent stage, clear Active Stage, show native desktop.
            val parked = task.copy(isVisible = false)
            registry.putStage(
                owner.copy(
                    tasks = listOf(parked),
                    isActive = false,
                    lastActiveTimeMs = now,
                ),
            )
            registry.pushToRecentTop(owner.stageId)
            bridge.parkNativeTasks(listOf(parked))
            if (wasActive) {
                bridge.showNativeDesktop()
            }
            return registry.snapshot()
        }

        // Multi-window stage: split task into its own single-window Stage at Recent Strip top.
        registry.detachTask(taskId)
        val parked = task.copy(isVisible = false)
        val splitStageId = registry.nextStageId()
        registry.putStage(
            Stage(
                stageId = splitStageId,
                tasks = listOf(parked),
                lastActiveTimeMs = now,
                isActive = false,
            ),
        )
        registry.pushToRecentTop(splitStageId)
        bridge.parkNativeTasks(listOf(parked))
        return registry.snapshot()
    }

    /**
     * S6: Real task closed, killed, or removed from system Recents.
     * Automatically destroys the owning Stage when its last real task vanishes.
     */
    fun onNativeTaskVanished(taskId: Int): StageSession {
        val owner = registry.findStageForTask(taskId) ?: return registry.snapshot()
        val wasActive = owner.isActive
        val remainingStage = registry.detachTask(taskId)
        if (remainingStage == null && wasActive) {
            bridge.showNativeDesktop()
        }
        return registry.snapshot()
    }

    /**
     * Request closing a task explicitly (e.g., from native window `···` menu -> Close).
     */
    fun closeTask(taskId: Int): StageSession {
        bridge.closeNativeTask(taskId)
        return onNativeTaskVanished(taskId)
    }

    /**
     * System restart / SystemUI crash recovery.
     */
    fun restoreFromSystemTasks(liveTasks: List<StageTask>): StageSession {
        registry.restoreFromLiveTasks(liveTasks)
        val maxZ = liveTasks.maxOfOrNull { it.zOrder } ?: 0
        if (maxZ > zOrderCounter) {
            zOrderCounter = maxZ
        }
        return registry.snapshot()
    }

    companion object {
        fun selectEvictionCandidate(
            tasks: List<StageTask>,
            policy: EvictionPolicy,
        ): StageTask {
            require(tasks.isNotEmpty()) { "Cannot select eviction candidate from empty task list" }
            return when (policy) {
                EvictionPolicy.LOWEST_Z_ORDER_THEN_EARLIEST ->
                    tasks.minWith(compareBy<StageTask> { it.zOrder }.thenBy { it.joinedAtMs }.thenBy { it.taskId })

                EvictionPolicy.EARLIEST_JOINED_THEN_LOWEST_Z ->
                    tasks.minWith(compareBy<StageTask> { it.joinedAtMs }.thenBy { it.zOrder }.thenBy { it.taskId })
            }
        }
    }
}

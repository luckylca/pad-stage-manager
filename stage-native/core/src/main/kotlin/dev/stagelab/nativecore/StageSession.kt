package dev.stagelab.nativecore

/**
 * Deterministic rule for selecting which existing window is evicted into a new single-window Stage
 * when a 5th window is added to a full 4-window Active Stage (S4).
 */
enum class EvictionPolicy {
    /** Evict the window with the lowest Z-order (tie-broken by earliest joinedAtMs, then taskId). */
    LOWEST_Z_ORDER_THEN_EARLIEST,

    /** Evict the window that entered the stage earliest (tie-broken by lowest zOrder, then taskId). */
    EARLIEST_JOINED_THEN_LOWEST_Z,
}

/**
 * Immutable snapshot of the entire dynamic Stage Manager logical session.
 */
data class StageSession(
    val activeStageId: String?,
    val stages: Map<String, Stage>,
    /** Most-recently-active first, containing inactive stage IDs (unbounded history). */
    val recentStageIds: List<String>,
    val evictionPolicy: EvictionPolicy = EvictionPolicy.LOWEST_Z_ORDER_THEN_EARLIEST,
) {
    val activeStage: Stage? get() = activeStageId?.let { stages[it] }

    /** At most 4 visible stages for the left Recent Strip; older stages remain in [recentStageIds]. */
    val visibleRecentStages: List<Stage>
        get() = recentStageIds
            .asSequence()
            .filter { it != activeStageId }
            .mapNotNull { stages[it] }
            .take(MAX_VISIBLE_RECENT_STAGES)
            .toList()

    val allRecentStages: List<Stage>
        get() = recentStageIds
            .asSequence()
            .filter { it != activeStageId }
            .mapNotNull { stages[it] }
            .toList()

    companion object {
        const val MAX_VISIBLE_RECENT_STAGES = 4

        val EMPTY = StageSession(
            activeStageId = null,
            stages = emptyMap(),
            recentStageIds = emptyList(),
        )
    }
}

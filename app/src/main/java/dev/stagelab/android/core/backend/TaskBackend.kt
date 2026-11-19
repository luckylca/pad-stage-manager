package dev.stagelab.android.core.backend

enum class BackendTier(val displayName: String) {
    PUBLIC_API("Public API"),
    SHIZUKU("ADB / Shizuku"),
    ROOT("KernelSU Root")
}

enum class TaskCapability(val displayName: String) {
    LAUNCH_APP_INTENTS("Launch apps"),
    READ_USAGE_HISTORY("Read usage history"),
    ENUMERATE_SYSTEM_TASKS("Enumerate system tasks"),
    HOST_LIVE_TASK_SURFACE("Host live task surfaces"),
    RESIZE_TASKS("Resize external tasks"),
    ROUTE_INPUT("Route input to hosted tasks")
}

enum class CapabilityAvailability {
    AVAILABLE,
    NEEDS_USER_PERMISSION,
    BLOCKED_BY_PLATFORM,
    NOT_IMPLEMENTED,
    NOT_PROBED
}

data class CapabilityState(
    val capability: TaskCapability,
    val availability: CapabilityAvailability,
    val note: String
)

data class BackendReport(
    val tier: BackendTier,
    val states: List<CapabilityState>
)

interface TaskBackend {
    val tier: BackendTier

    fun inspect(): BackendReport
}

package dev.stagelab.android.core.backend

class PublicApiTaskBackend : TaskBackend {
    override val tier: BackendTier = BackendTier.PUBLIC_API

    override fun inspect(): BackendReport = BackendReport(
        tier = tier,
        states = listOf(
            CapabilityState(
                TaskCapability.LAUNCH_APP_INTENTS,
                CapabilityAvailability.AVAILABLE,
                "Standard activity launch intents are available to ordinary apps."
            ),
            CapabilityState(
                TaskCapability.READ_USAGE_HISTORY,
                CapabilityAvailability.NEEDS_USER_PERMISSION,
                "Usage Access must be granted by the user in Android Settings."
            ),
            CapabilityState(
                TaskCapability.ENUMERATE_SYSTEM_TASKS,
                CapabilityAvailability.BLOCKED_BY_PLATFORM,
                "Ordinary third-party apps do not receive the full system task list."
            ),
            CapabilityState(
                TaskCapability.HOST_LIVE_TASK_SURFACE,
                CapabilityAvailability.BLOCKED_BY_PLATFORM,
                "Live cross-app task hosting requires privileged task and surface APIs."
            ),
            CapabilityState(
                TaskCapability.RESIZE_TASKS,
                CapabilityAvailability.NOT_IMPLEMENTED,
                "Window resize support has not been implemented or device-tested."
            ),
            CapabilityState(
                TaskCapability.ROUTE_INPUT,
                CapabilityAvailability.NOT_IMPLEMENTED,
                "Hosted-task input and focus routing remain part of the PoC."
            )
        )
    )
}

package dev.museframework.engine.api.execution.policy

/** The admission decision for one submitted background body; a rejection is caller-owned. */
sealed interface BackgroundAdmission {
    data class Accepted(val task: BackgroundTask) : BackgroundAdmission

    data class Rejected(val reason: BackgroundRejection) : BackgroundAdmission
}

enum class BackgroundRejection {
    CAPACITY,
    STOPPING,
    INPUT_NOT_RETAINABLE,
}

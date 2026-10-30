package dev.museframework.engine.api.execution.policy

data class PolicyCompletion(
    val task: PolicyTask,
    val outcome: PolicyOutcome,
)

package dev.museframework.engine.api.execution.policy

fun interface ExecutionPolicy {
    suspend fun execute(invocation: PolicyInvocation): PolicyOutcome
}

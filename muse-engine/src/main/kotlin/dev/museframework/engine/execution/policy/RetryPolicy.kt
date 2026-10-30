package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration

internal class RetryPolicy(
    private val body: BodyHandle,
    private val maxRetries: Int,
    private val delayMillis: Long,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome {
        var last: PolicyOutcome.Failure? = null
        for (attempt in 0..maxRetries) {
            when (val result = invocation.call(body, invocation.input)) {
                is PolicyOutcome.Success -> return result
                is PolicyOutcome.Failure -> last = result
            }
            if (attempt < maxRetries) invocation.pause(delayMillis)
        }
        val failure = checkNotNull(last)
        return PolicyOutcome.Failure(
            MuseError(
                description = "Retry attempts exhausted",
                detailedDescription = failure.error.description,
                errorType = ErrorType.Mule.RETRY_EXHAUSTED,
                cause = failure.error.cause,
                childErrors = listOf(failure.error),
            ),
            invocation.input,
        )
    }
}

internal val untilSuccessfulPolicyDeclaration =
    PolicyRouterDeclaration<UntilSuccessfulConfig> { component, definition, _ ->
        require(component.maxRetries >= 0) { "Maximum retries must be nonnegative" }
        require(component.millisBetweenRetries >= 0) { "Retry delay must be nonnegative" }
        val body = definition.inline(component.pipeline)
        PolicyFactory { RetryPolicy(body, component.maxRetries, component.millisBetweenRetries) }
    }

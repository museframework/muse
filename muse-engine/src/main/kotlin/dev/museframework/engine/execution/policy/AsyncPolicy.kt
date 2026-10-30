package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.AsyncConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.policy.BackgroundAdmission
import dev.museframework.engine.api.execution.policy.BackgroundHandle
import dev.museframework.engine.api.execution.policy.BackgroundRejection
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration

/**
 * Standard async policy: one submitted branch per event, owned by the application rather than by
 * the calling request. An accepted branch never merges a result into the caller's event.
 *
 * A rejection is a normal caller-owned domain failure: admission is bounded by the application's
 * capacity and by this declaration's shared running limit, and request-bound input must be
 * retainable before the branch may outlive the request that produced it.
 */
internal class AsyncPolicy(
    private val background: BackgroundHandle,
    private val body: BodyHandle,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome =
        when (val admission = invocation.submit(background, body)) {
            is BackgroundAdmission.Accepted -> PolicyOutcome.Success(invocation.input)
            is BackgroundAdmission.Rejected ->
                PolicyOutcome.Failure(
                    MuseError(
                        description = "Async work was not accepted",
                        detailedDescription = admission.reason.name,
                        errorType =
                            if (admission.reason == BackgroundRejection.INPUT_NOT_RETAINABLE) {
                                ErrorType.Mule.ASYNC_INPUT_NOT_RETAINABLE
                            } else {
                                ErrorType.Mule.ASYNC_REJECTED
                            },
                        cause = IllegalStateException(admission.reason.name),
                    ),
                    invocation.input,
                )
        }
}

/** Standard managed declaration of the async router: prepared occurrence, shared limit, owned branches. */
internal val asyncPolicyDeclaration =
    PolicyRouterDeclaration<AsyncConfig> { component, definition, _ ->
        val body = definition.inline(component.pipeline)
        val background = definition.background(maxConcurrency = component.maxConcurrency)
        PolicyFactory { AsyncPolicy(background, body) }
    }

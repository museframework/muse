package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.model.component.Router
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.ResolvedErrorHandler
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.streaming.StreamingEventResources
import dev.museframework.engine.execution.BoundExecution
import dev.museframework.engine.execution.OwnedExecution

internal class PolicyProcessorAdapter(
    override val component: Router,
    private val application: PolicyBindings,
    private val binding: Any,
    private val policy: ExecutionPolicy,
    handler: ResolvedErrorHandler?,
) : MuseProcessor {
    private val algorithm = BoundExecution { context ->
        val invocation = ManagedPolicyInvocation(application, binding, context)
        try {
            val outcome = policy.execute(invocation)
            StreamingEventResources.registerEventProviders(outcome.event, context.streamingResources)
            when (outcome) {
                is PolicyOutcome.Success -> ProcessorResult.Success(
                    context.withEvent(outcome.event.copy(error = context.event.error)),
                )
                is PolicyOutcome.Failure -> ProcessorResult.Failure(
                    outcome.error, context.withEvent(outcome.event.copy(error = outcome.error)),
                )
            }
        } finally {
            invocation.close()
        }
    }
    private val execution = if (handler == null) algorithm else OwnedExecution(algorithm, handler)

    override suspend fun process(context: ExecutionContext): ProcessorResult {
        application.checkActive()
        return execution.invoke(context)
    }
}

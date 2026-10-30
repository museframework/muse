package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import java.util.UUID
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration

internal class CorrelationPolicy(
    private val component: WithCorrelationIdConfig,
    private val body: BodyHandle,
    private val expressionEvaluator: MuseExpressionEvaluator,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome {
        val outerCorrelationId = invocation.input.correlationId
        val correlationId = try {
            expressionEvaluator.evaluate(component.correlationId, invocation.input)?.value?.toString()
                ?: UUID.randomUUID().toString()
        } catch (exception: MuseExpressionException) {
            val error = MuseError(
                description = "Correlation ID evaluation failed",
                detailedDescription = exception.message ?: "Expression evaluation failed",
                errorType = ErrorType.Mule.EXPRESSION,
                cause = exception,
                sourceFile = component.sourceFile,
                sourceLine = component.sourceLine,
                sourceClass = component.sourceClass,
            )
            return PolicyOutcome.Failure(error, invocation.input.copy(error = error))
        }

        return when (val outcome = invocation.call(body, invocation.input.copy(correlationId = correlationId))) {
            is PolicyOutcome.Success -> PolicyOutcome.Success(outcome.event.copy(correlationId = outerCorrelationId))
            is PolicyOutcome.Failure -> PolicyOutcome.Failure(
                outcome.error,
                outcome.event.copy(correlationId = outerCorrelationId),
            )
        }
    }
}

internal val withCorrelationIdPolicyDeclaration =
    PolicyRouterDeclaration<WithCorrelationIdConfig> { component, definition, context ->
        val body = definition.inline(component.pipeline)
        PolicyFactory { CorrelationPolicy(component, body, context.expressionEvaluator) }
    }

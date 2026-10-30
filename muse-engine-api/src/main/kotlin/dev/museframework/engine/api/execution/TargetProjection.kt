package dev.museframework.engine.api.execution

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.api.streaming.StreamingEventResources

fun ExecutionContext.projectTarget(
    outcome: PolicyOutcome,
    target: TargetConfig,
    base: MuseEvent = event,
): PolicyOutcome {
    StreamingEventResources.registerEventProviders(outcome.event, streamingResources)
    if (outcome is PolicyOutcome.Failure) return outcome

    return try {
        val expression = target.targetValue
        val targetValue = if (expression == null) {
            outcome.event.payload
        } else {
            expressionEvaluator.evaluate(expression, outcome.event)
        }
        StreamingEventResources.registerValueProviders(targetValue, streamingResources)
        val projected = base.copy(variables = base.variables + (target.target to targetValue))
        StreamingEventResources.registerEventProviders(projected, streamingResources)
        PolicyOutcome.Success(projected)
    } catch (exception: MuseExpressionException) {
        val error = MuseError(
            description = "Target value evaluation failed",
            detailedDescription = exception.message ?: "Expression evaluation failed",
            errorType = ErrorType.Mule.EXPRESSION,
            cause = exception,
            sourceFile = currentComponent?.sourceFile,
            sourceLine = currentComponent?.sourceLine,
            sourceClass = currentComponent?.sourceClass,
        )
        PolicyOutcome.Failure(error, base.copy(error = error))
    }
}

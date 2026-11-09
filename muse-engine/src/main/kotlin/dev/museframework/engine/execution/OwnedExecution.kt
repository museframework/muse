package dev.museframework.engine.execution

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.error.isDescendantOf
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.ResolvedErrorHandler
import dev.museframework.engine.api.execution.ResolvedErrorHandlerStrategy
import dev.museframework.engine.api.expression.MuseExpressionException

/**
 * Owner semantics for one prepared body.
 *
 * A failure produced by [body] is either handled in this owner by its own strategy or returned to
 * the caller as a terminal failure; the enclosing owner is never consulted before this decision,
 * so intermediate failures inside retries, iterations and parallel branches stay local.
 */
internal class OwnedExecution(
    private val body: BoundExecution,
    private val handler: ResolvedErrorHandler?,
) : BoundExecution {
    override suspend fun invoke(context: ExecutionContext): ProcessorResult {
        val ambientError = context.event.error
        var outcome = body.invoke(context).normalizeOwnerReturn(context, ambientError)
        var strategyActive = false

        while (outcome is ProcessorResult.Failure && !strategyActive) {
            val failure = outcome
            val errorContext = context.withEvent(failure.context.event.copy(error = failure.error))
            val strategy =
                try {
                    handler?.strategies?.firstOrNull { candidate ->
                        failure.error.errorType.isDescendantOf(candidate.errorType) &&
                            candidate.predicateMatches(errorContext)
                    }
                } catch (exception: MuseExpressionException) {
                    val predicateError =
                        MuseError(
                            description = "Error handler predicate evaluation failed",
                            detailedDescription = exception.message ?: "Expression evaluation failed",
                            errorType = ErrorType.Mule.EXPRESSION,
                            cause = exception,
                            childErrors = listOf(failure.error),
                        )
                    return ProcessorResult
                        .Failure(predicateError, errorContext)
                        .normalizeOwnerReturn(context, ambientError)
                }

            if (strategy == null) {
                return failure
            }

            strategyActive = true
            val strategyOutcome = errorContext.runUnhandled(strategy.pipeline)
            outcome =
                when (strategyOutcome) {
                    is ProcessorResult.Success ->
                        when (strategy.kind) {
                            ResolvedErrorHandlerStrategy.Kind.CONTINUE -> strategyOutcome
                            ResolvedErrorHandlerStrategy.Kind.PROPAGATE ->
                                ProcessorResult.Failure(failure.error, strategyOutcome.context)
                        }

                    is ProcessorResult.Failure -> strategyOutcome
                }.normalizeOwnerReturn(context, ambientError)
        }

        return outcome
    }
}

private fun ResolvedErrorHandlerStrategy.predicateMatches(context: ExecutionContext): Boolean {
    val expression = whenExpression ?: return true
    val value = context.expressionEvaluator.evaluate(expression, context.event)?.value
    return value == true || value == "true"
}

private fun ProcessorResult.normalizeOwnerReturn(
    entry: ExecutionContext,
    ambientError: MuseError?,
): ProcessorResult =
    when (this) {
        is ProcessorResult.Success ->
            mapTerminalContext { terminal ->
                entry.withEvent(terminal.event.copy(error = ambientError))
            }

        is ProcessorResult.Failure ->
            mapTerminalContext { terminal ->
                entry.withEvent(terminal.event.copy(error = error))
            }
    }

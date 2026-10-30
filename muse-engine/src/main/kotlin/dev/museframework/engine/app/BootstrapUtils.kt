package dev.museframework.engine.app

import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.engine.api.execution.Pipeline
import dev.museframework.engine.api.execution.ResolvedErrorHandler
import dev.museframework.engine.api.execution.ResolvedErrorHandlerStrategy

/**
 * Resolves a declared handler into its executable form.
 *
 * Bodies are supplied by the caller's already prepared occurrences: a handler never compiles a
 * nested policy or referenced flow by itself.
 */
internal fun ErrorHandler.resolve(
    pipeline: (ErrorHandlerStrategy, Int) -> Pipeline,
): ResolvedErrorHandler =
    ResolvedErrorHandler(
        strategies = strategies.mapIndexed { index, strategy ->
            ResolvedErrorHandlerStrategy(
                errorType = strategy.errorType,
                pipeline = pipeline(strategy, index),
                kind = when (strategy) {
                    is ErrorHandlerStrategy.OnErrorContinue -> ResolvedErrorHandlerStrategy.Kind.CONTINUE
                    is ErrorHandlerStrategy.OnErrorPropagate -> ResolvedErrorHandlerStrategy.Kind.PROPAGATE
                },
                whenExpression = strategy.whenExpression,
                enableNotifications = strategy.enableNotifications,
                logException = strategy.logException,
            )
        }
    )

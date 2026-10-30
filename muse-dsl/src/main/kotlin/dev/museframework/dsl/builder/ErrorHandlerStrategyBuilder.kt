package dev.museframework.dsl.builder

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.error.ErrorType

@MuseDsl
class OnErrorStrategyBuilder {
    var whenExpression: String? = null
    var enableNotifications: Boolean = true
    var logException: Boolean = true
}

@MuseDsl
class ErrorHandlerStrategyBuilder {
    private val strategies = mutableListOf<ErrorHandlerStrategy>()

    fun onErrorContinue(
        errorType: ErrorType,
        block: PipelineBuilder.() -> Unit,
    ) {
        strategies.add(
            ErrorHandlerStrategy.OnErrorContinue(
                errorType = errorType,
                pipeline = PipelineBuilder().apply(block).build(),
            )
        )
    }

    fun onErrorContinue(
        errorType: ErrorType,
        configure: OnErrorStrategyBuilder.() -> Unit = {},
        pipeline: PipelineBuilder.() -> Unit,
    ) {
        val config = OnErrorStrategyBuilder().apply(configure)
        strategies.add(
            ErrorHandlerStrategy.OnErrorContinue(
                errorType = errorType,
                pipeline = PipelineBuilder().apply(pipeline).build(),
                whenExpression = config.whenExpression,
                enableNotifications = config.enableNotifications,
                logException = config.logException,
            )
        )
    }

    fun onErrorPropagate(
        errorType: ErrorType,
        block: PipelineBuilder.() -> Unit,
    ) {
        strategies.add(
            ErrorHandlerStrategy.OnErrorPropagate(
                errorType = errorType,
                pipeline = PipelineBuilder().apply(block).build(),
            )
        )
    }

    fun onErrorPropagate(
        errorType: ErrorType,
        configure: OnErrorStrategyBuilder.() -> Unit = {},
        pipeline: PipelineBuilder.() -> Unit,
    ) {
        val config = OnErrorStrategyBuilder().apply(configure)
        strategies.add(
            ErrorHandlerStrategy.OnErrorPropagate(
                errorType = errorType,
                pipeline = PipelineBuilder().apply(pipeline).build(),
                whenExpression = config.whenExpression,
                enableNotifications = config.enableNotifications,
                logException = config.logException,
            )
        )
    }

    fun build(): ErrorHandler = ErrorHandler(strategies.toList())
}

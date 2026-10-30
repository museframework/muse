package dev.museframework.dsl.api.model.error

import dev.museframework.dsl.api.model.component.PipelineElement

sealed class ErrorHandlerStrategy {
    abstract val errorType: ErrorType
    abstract val pipeline: List<PipelineElement>
    abstract val whenExpression: String?
    abstract val enableNotifications: Boolean
    abstract val logException: Boolean

    data class OnErrorContinue(
        override val errorType: ErrorType,
        override val pipeline: List<PipelineElement>,
        override val whenExpression: String? = null,
        override val enableNotifications: Boolean = true,
        override val logException: Boolean = true,
    ) : ErrorHandlerStrategy()

    data class OnErrorPropagate(
        override val errorType: ErrorType,
        override val pipeline: List<PipelineElement>,
        override val whenExpression: String? = null,
        override val enableNotifications: Boolean = true,
        override val logException: Boolean = true,
    ) : ErrorHandlerStrategy()
}

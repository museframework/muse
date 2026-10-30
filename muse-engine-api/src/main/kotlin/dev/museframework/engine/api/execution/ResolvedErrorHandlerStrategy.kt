package dev.museframework.engine.api.execution

import dev.museframework.dsl.api.model.error.ErrorType

data class ResolvedErrorHandlerStrategy(
    val errorType: ErrorType,
    val pipeline: Pipeline,
    val kind: Kind,
    val whenExpression: String? = null,
    val enableNotifications: Boolean = true,
    val logException: Boolean = true,
) {
    enum class Kind { CONTINUE, PROPAGATE }
}

data class ResolvedErrorHandler(
    val strategies: List<ResolvedErrorHandlerStrategy>,
) {
    companion object {
        val EMPTY = ResolvedErrorHandler(strategies = emptyList())
    }
}

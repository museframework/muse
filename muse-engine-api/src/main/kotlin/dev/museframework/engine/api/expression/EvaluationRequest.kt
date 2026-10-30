package dev.museframework.engine.api.expression

/** Requested consumption shape; independent of MIME type, replay strategy, and input ownership. */
sealed interface EvaluationRequest {
    data class Value(val expression: String) : EvaluationRequest
    data class Iteration(val source: IterationSource) : EvaluationRequest
}

/** An absent foreach expression consumes the original payload without ordinary evaluation. */
sealed interface IterationSource {
    data object EventPayload : IterationSource
    data class Expression(val text: String) : IterationSource
}

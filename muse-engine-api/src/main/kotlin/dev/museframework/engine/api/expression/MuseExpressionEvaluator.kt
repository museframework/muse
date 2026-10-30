package dev.museframework.engine.api.expression

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue

interface MuseExpressionEvaluator {
    fun evaluate(
        expression: String,
        event: MuseEvent,
    ): TypedValue<Any>?

    /** A single value, which may itself be streaming; preserves the existing contract and metadata. */
    fun evaluate(request: EvaluationRequest.Value, event: MuseEvent): TypedValue<Any>? =
        evaluate(request.expression, event)

    /** Splits an expression into typed elements. Close the result on every exit. */
    fun split(expression: String, event: MuseEvent): ExpressionSplit =
        split(EvaluationRequest.Iteration(IterationSource.Expression(expression)), event)

    /**
     * Explicit split source. Custom evaluators retain their compatible iteration dispatch.
     * The built-in evaluator delegates to backend split without ordinary value evaluation.
     */
    fun split(request: EvaluationRequest.Iteration, event: MuseEvent): ExpressionSplit =
        ExpressionSplit.fromIteration(openIteration(request, event))

    /**
     * Opens one caller-owned iteration. Close the result on every exit.
     * Result shape does not prescribe input repeatability or guarantee incremental evaluation.
     * The compatible fallback may materialize the result before returning.
     */
    fun openIteration(request: EvaluationRequest.Iteration, event: MuseEvent): ExpressionIteration =
        evaluate(request, event)

    /**
     * Explicit element consumption. The caller owns the returned result and must close it.
     * Compatibility entry point; consumers should prefer [openIteration] to make ownership explicit.
     * The default snapshots collections, arrays and all Iterables, including lazy Iterables.
     */
    fun evaluate(request: EvaluationRequest.Iteration, event: MuseEvent): ExpressionIteration {
        val value = when (val source = request.source) {
            IterationSource.EventPayload -> event.payload?.value
            is IterationSource.Expression -> evaluate(EvaluationRequest.Value(source.text), event)?.value
        }
        return ExpressionIteration.snapshot(value)
    }
}

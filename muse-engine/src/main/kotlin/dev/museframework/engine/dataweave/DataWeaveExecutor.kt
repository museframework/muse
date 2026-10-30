package dev.museframework.engine.dataweave

import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.ExpressionIteration
import dev.museframework.engine.api.expression.ExpressionSplit

interface DataWeaveExecutor {
    fun evaluate(expression: String, context: DataWeaveContext): TypedValue<Any>?
    fun evaluateResource(resourcePath: String, context: DataWeaveContext): TypedValue<Any>?

    /** Compatibility default for custom backends; the stock backend implements native splitting. */
    fun split(expression: String, context: DataWeaveContext): ExpressionSplit =
        ExpressionSplit.fromIteration(
            tryOpenIteration(expression, context) ?: ExpressionIteration.snapshot(evaluate(expression, context)?.value),
        )

    /** Returns null without evaluating or consuming input when lazy iteration is unsupported. */
    fun tryOpenIteration(expression: String, context: DataWeaveContext): ExpressionIteration? = null
}

fun DataWeaveExecutor.evaluateDwExpression(
    expression: DwExpression,
    context: DataWeaveContext,
): TypedValue<Any>? = when (expression) {
    is DwExpression.Inline -> evaluate(expression.expression, context)
    is DwExpression.Resource -> evaluateResource(expression.path, context)
}

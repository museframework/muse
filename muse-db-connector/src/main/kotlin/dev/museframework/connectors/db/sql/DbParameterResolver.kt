package dev.museframework.connectors.db.sql

import dev.museframework.connectors.db.DbParameterException
import dev.museframework.db.api.model.DbBatchParameterSource
import dev.museframework.db.api.model.DbParameterSource
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.expression.MuseExpressionEvaluator

internal class DbParameterResolver(
    private val expressionEvaluator: MuseExpressionEvaluator,
) {
    fun resolve(
        source: DbParameterSource,
        event: MuseEvent,
    ): Map<String, Any?> =
        when (source) {
            is DbParameterSource.Values -> source.values.mapValues { (_, value) -> evaluateValue(value, event) }
            is DbParameterSource.Expression -> toParameterMap(evaluateExpression(source.expression, event))
        }

    fun resolveBatch(
        source: DbBatchParameterSource,
        event: MuseEvent,
    ): List<Map<String, Any?>> {
        val rows: Iterable<*> =
            when (source) {
                is DbBatchParameterSource.Values -> source.values
                is DbBatchParameterSource.Expression ->
                    when (val value = evaluateExpression(source.expression, event)) {
                        is Iterable<*> -> value
                        is Array<*> -> value.asIterable()
                        else -> throw DbParameterException("Batch parameter expression must evaluate to an iterable of maps")
                    }
            }
        return rows.map { row ->
            toParameterMap(row).mapValues { (_, value) -> evaluateValue(value, event) }
        }
    }

    private fun evaluateExpression(
        expression: String,
        event: MuseEvent,
    ): Any? {
        val evaluated = expressionEvaluator.evaluate(expression, event)
            ?: throw DbParameterException("Parameter expression '$expression' did not produce a value")
        return evaluated.getOrNull()
    }

    private fun evaluateValue(
        value: Any?,
        event: MuseEvent,
    ): Any? {
        if (value !is String) return value
        val evaluated = expressionEvaluator.evaluate(value, event) ?: return value
        return evaluated.getOrNull()
    }

    private fun toParameterMap(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *>
            ?: throw DbParameterException("Input parameters must evaluate to a map")
        val result = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, parameterValue) ->
            if (key !is String || key.isBlank()) {
                throw DbParameterException("Input parameter names must be non-blank strings")
            }
            result[key] = parameterValue
        }
        return result
    }
}

package dev.museframework.engine.expression

import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.EvaluationRequest
import dev.museframework.engine.api.expression.IterationSource
import dev.museframework.engine.api.expression.ExpressionIteration
import dev.museframework.engine.api.expression.ExpressionSplit
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.DataWeaveExecutor
import dev.museframework.engine.properties.PropertiesLoader
import java.util.*

/**
 * Properties wrapper that uses Spring Boot-style relaxed binding.
 * Delegates to PropertiesLoader for property resolution.
 */
class ResolvableProperties(private val delegate: Properties) : Properties() {
    override fun getProperty(key: String): String? {
        return PropertiesLoader.resolveProperty(key, delegate)
    }

    override fun getProperty(key: String, defaultValue: String): String {
        return getProperty(key) ?: defaultValue
    }
}

class MuseExpressionEvaluatorImpl(
    private val dataWeaveExecutor: DataWeaveExecutor,
    private val properties: Properties,
) : MuseExpressionEvaluator {
    companion object {
        // Matches a pure DW expression: #[...] with support for nested brackets (e.g. #[['a','b']])
        private val SINGLE_EXPRESSION = Regex("^#\\[(.*)]$", RegexOption.DOT_MATCHES_ALL)

        // Matches interpolated DW expressions within a string, supporting one level of bracket nesting
        private val INTERPOLATION = Regex("#\\[((?:[^\\[\\]]*|\\[[^\\]]*])*)]")
    }

    override fun split(request: EvaluationRequest.Iteration, event: MuseEvent): ExpressionSplit {
        val expression = when (val source = request.source) {
            IterationSource.EventPayload -> "payload"
            is IterationSource.Expression -> SINGLE_EXPRESSION.matchEntire(source.text)?.groupValues?.get(1)
                ?: throw MuseExpressionException("Split requires a single #[...] expression")
        }
        return dataWeaveExecutor.split(expression, DataWeaveContext(event, ResolvableProperties(properties)))
    }

    override fun evaluate(request: EvaluationRequest.Iteration, event: MuseEvent): ExpressionIteration {
        val body = when (val source = request.source) {
            IterationSource.EventPayload -> "payload"
            is IterationSource.Expression -> SINGLE_EXPRESSION.matchEntire(source.text)?.groupValues?.get(1)
        }
        if (body != null) {
            val context = DataWeaveContext(event, ResolvableProperties(properties))
            dataWeaveExecutor.tryOpenIteration(body, context)?.let { return it }
        }
        return super<MuseExpressionEvaluator>.evaluate(request, event)
    }

    override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? {
        // Wrap properties with Spring Boot-style resolution
        val resolvableProperties = ResolvableProperties(properties)
        val context = DataWeaveContext(event = event, properties = resolvableProperties)
        val exprCount = countOccurrences(expression, "#[")
        if (exprCount == 0) return TypedValue.of(expression, TypedValue.MIME_TEXT)

        if (exprCount == 1) {
            val singleMatch = SINGLE_EXPRESSION.matchEntire(expression)
            if (singleMatch != null) {
                return dataWeaveExecutor.evaluate(singleMatch.groupValues[1], context)
            }
        }

        val result = INTERPOLATION.replace(expression) { match ->
            dataWeaveExecutor.evaluate(match.groupValues[1], context)
                ?.value?.toString() ?: ""
        }
        return TypedValue.of(result, TypedValue.MIME_TEXT)
    }

    private fun countOccurrences(text: String, sub: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = text.indexOf(sub, idx)
            if (idx < 0) break
            count++
            idx += sub.length
        }
        return count
    }

}

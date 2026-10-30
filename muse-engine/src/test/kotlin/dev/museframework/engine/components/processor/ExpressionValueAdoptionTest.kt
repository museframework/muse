package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class ExpressionValueAdoptionTest {
    @Test
    fun `payload assignment adopts nested expression providers before returning`() = runTest {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("payload".toByteArray())
        val resultValue = TypedValue.of<Any>(mapOf("stream" to listOf(provider)))
        val evaluator = evaluator(resultValue)
        val registry = StreamingResourceRegistry()
        try {
            val context = ExecutionContext(MuseEvent(), null, coroutineScope = this, expressionEvaluator = evaluator, streamingResources = registry)
            val result = SetPayloadMuseProcessor(SetPayloadConfig(docName = "set", docId = "set", value = "fresh"), evaluator).process(context)
            assertSame(resultValue, assertIs<ProcessorResult.Success>(result).context.event.payload)
            assertTrue(provider.isManaged)
        } finally {
            registry.releaseAll()
            provider.releaseResources()
        }
        assertTrue(provider.isClosed)
    }

    @Test
    fun `variable assignment adopts nested expression providers before returning`() = runTest {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("variable".toByteArray())
        val resultValue = TypedValue.of<Any>(arrayOf(mapOf("stream" to provider)))
        val evaluator = evaluator(resultValue)
        val registry = StreamingResourceRegistry()
        try {
            val context = ExecutionContext(MuseEvent(), null, coroutineScope = this, expressionEvaluator = evaluator, streamingResources = registry)
            val config = SetVariableConfig(docName = "set", docId = "set", variableName = "value", value = "fresh")
            val result = SetVariableMuseProcessor(config, evaluator).process(context)
            assertSame(resultValue, assertIs<ProcessorResult.Success>(result).context.event.variables["value"])
            assertTrue(provider.isManaged)
        } finally {
            registry.releaseAll()
            provider.releaseResources()
        }
        assertTrue(provider.isClosed)
    }

    private fun evaluator(value: TypedValue<Any>) = object : MuseExpressionEvaluator {
        override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any> = value
    }
}

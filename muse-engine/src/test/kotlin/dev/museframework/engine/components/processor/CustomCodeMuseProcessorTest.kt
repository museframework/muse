package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.CustomCodeConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CustomCodeMuseProcessorTest {

    private val expressionEvaluator = mockk<MuseExpressionEvaluator>()

    private fun context(event: MuseEvent) = runBlocking {
        ExecutionContext(
            event = event,
            errorHandler = null,
            coroutineScope = this,
            expressionEvaluator = expressionEvaluator,
        )
    }

    @Test
    fun `should transform payload with custom code`() = runBlocking {
        val ctx = context(MuseEvent(payload = TypedValue.of("hello")))
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { payload -> (payload as String).uppercase() },
        )
        val processor = CustomCodeMuseProcessor(config)

        val result = processor.process(ctx)

        assertInstanceOf(ProcessorResult.Success::class.java, result)
        val success = result as ProcessorResult.Success
        assertEquals("HELLO", success.context.event.payload?.value)
    }

    @Test
    fun `should handle null payload`() = runBlocking {
        val ctx = context(MuseEvent())
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { payload -> payload ?: "default" },
        )
        val processor = CustomCodeMuseProcessor(config)

        val result = processor.process(ctx)

        val success = result as ProcessorResult.Success
        assertEquals("default", success.context.event.payload?.value)
    }

    @Test
    fun `should return null when code returns null`() = runBlocking {
        val ctx = context(MuseEvent(payload = TypedValue.of("value")))
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { _ -> null },
        )
        val processor = CustomCodeMuseProcessor(config)

        val result = processor.process(ctx)

        val success = result as ProcessorResult.Success
        assertNull(success.context.event.payload?.value)
    }

    @Test
    fun `should handle complex object transformation`() = runBlocking {
        val input = mapOf("name" to "John", "age" to 30)
        val ctx = context(MuseEvent(payload = TypedValue.of(input)))
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { payload ->
                val map = payload as Map<*, *>
                mapOf(
                    "name" to (map["name"] as String).uppercase(),
                    "age" to (map["age"] as Int) + 1,
                )
            },
        )
        val processor = CustomCodeMuseProcessor(config)

        val result = processor.process(ctx)

        val success = result as ProcessorResult.Success
        val output = success.context.event.payload?.value as Map<*, *>
        assertEquals("JOHN", output["name"])
        assertEquals(31, output["age"])
    }

    @Test
    fun `should handle exceptions with error handling enabled`() = runBlocking {
        val ctx = context(MuseEvent(payload = TypedValue.of("value")))
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { _ -> throw RuntimeException("Custom error") },
            handleErrors = true,
        )
        val processor = CustomCodeMuseProcessor(config)

        val result = processor.process(ctx)

        assertInstanceOf(ProcessorResult.Failure::class.java, result)
        val failure = result as ProcessorResult.Failure
        assertTrue(failure.error.description.contains("Custom error"))
    }

    @Test
    fun `should throw exception when error handling disabled`() {
        val ctx = context(MuseEvent(payload = TypedValue.of("value")))
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { _ -> throw RuntimeException("Custom error") },
            handleErrors = false,
        )
        val processor = CustomCodeMuseProcessor(config)

        assertThrows(RuntimeException::class.java) {
            runBlocking { processor.process(ctx) }
        }
    }

    @Test
    fun `should preserve variables and attributes`() = runBlocking {
        val event = MuseEvent(
            payload = TypedValue.of("payload"),
            attributes = TypedValue.of(mapOf("attr" to "value")),
            variables = mapOf("var" to TypedValue.of("variable")),
        )
        val ctx = context(event)
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { payload -> "transformed: $payload" },
        )
        val processor = CustomCodeMuseProcessor(config)

        val result = processor.process(ctx)

        val success = result as ProcessorResult.Success
        assertEquals("transformed: payload", success.context.event.payload?.value)
        assertEquals("value", (success.context.event.attributes?.value as Map<*, *>)["attr"])
        assertEquals("variable", success.context.event.variables["var"]?.value)
    }

    @Test
    fun `should handle list transformation`() = runBlocking {
        val input = listOf(1, 2, 3, 4, 5)
        val ctx = context(MuseEvent(payload = TypedValue.of(input)))
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { payload ->
                val list = payload as List<*>
                list.filterIsInstance<Int>().map { it * 2 }
            },
        )
        val processor = CustomCodeMuseProcessor(config)

        val result = processor.process(ctx)

        val success = result as ProcessorResult.Success
        val output = success.context.event.payload?.value as List<*>
        assertEquals(listOf(2, 4, 6, 8, 10), output)
    }

    @Test
    fun `should handle async operations in custom code`() = runBlocking {
        val ctx = context(MuseEvent(payload = TypedValue.of("input")))
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { payload ->
                kotlinx.coroutines.delay(10)
                "processed: $payload"
            },
        )
        val processor = CustomCodeMuseProcessor(config)

        val result = processor.process(ctx)

        val success = result as ProcessorResult.Success
        assertEquals("processed: input", success.context.event.payload?.value)
    }

    @Test
    fun `should not modify original context`() = runBlocking {
        val originalEvent = MuseEvent(payload = TypedValue.of("original"))
        val ctx = context(originalEvent)
        val config = CustomCodeConfig(
            docName = "test", docId = "test-id",
            code = { _ -> "modified" },
        )
        val processor = CustomCodeMuseProcessor(config)

        val originalPayloadBefore = ctx.event.payload?.value
        val result = processor.process(ctx)
        val originalPayloadAfter = ctx.event.payload?.value

        assertEquals(originalPayloadBefore, originalPayloadAfter)
        assertEquals("original", originalPayloadAfter)

        val success = result as ProcessorResult.Success
        assertEquals("modified", success.context.event.payload?.value)
    }
}

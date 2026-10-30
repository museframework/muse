package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SetPayloadMuseProcessorTest {
    private val expressionEvaluator = mockk<MuseExpressionEvaluatorImpl>()

    private fun context(event: MuseEvent) =
        runBlocking {
            ExecutionContext(
                event = event,
                errorHandler = null,
                coroutineScope = this,
                expressionEvaluator = expressionEvaluator,
            )
        }

    @Test
    fun `should set payload with literal value`() =
        runBlocking {
            val ctx =
                ExecutionContext(
                    event = MuseEvent(),
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetPayloadConfig(
                    docName = "test",
                    docId = "test-id",
                    value = "hello world",
                )
            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("hello world", any()) } returns TypedValue.of("hello world", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "hello world",
                success.context.event.payload
                    ?.value,
            )
        }

    @Test
    fun `should set payload with expression value`() =
        runBlocking {
            val event = MuseEvent(payload = TypedValue.of(mapOf("name" to "John")))
            val ctx =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetPayloadConfig(
                    docName = "test",
                    docId = "test-id",
                    value = "#[payload.name]",
                )
            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("#[payload.name]", any()) } returns TypedValue.of("John")

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                "John",
                success.context.event.payload
                    ?.value,
            )
        }

    @Test
    fun `should set payload with string interpolation`() =
        runBlocking {
            val event = MuseEvent(payload = TypedValue.of("World"))
            val ctx =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetPayloadConfig(
                    docName = "test",
                    docId = "test-id",
                    value = "Hello #[payload]!",
                )
            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("Hello #[payload]!", any()) } returns TypedValue.of("Hello World!", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                "Hello World!",
                success.context.event.payload
                    ?.value,
            )
        }

    @Test
    fun `should apply mimeType when set`() =
        runBlocking {
            val ctx =
                ExecutionContext(
                    event = MuseEvent(),
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetPayloadConfig(
                    docName = "test",
                    docId = "test-id",
                    value = "{}",
                    mimeType = "application/json",
                )
            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("{}", any()) } returns TypedValue.of("{}", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                "application/json",
                success.context.event.payload
                    ?.mimeType,
            )
        }

    @Test
    fun `should not override mimeType when not set`() =
        runBlocking {
            val ctx =
                ExecutionContext(
                    event = MuseEvent(),
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetPayloadConfig(
                    docName = "test",
                    docId = "test-id",
                    value = "hello",
                )
            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("hello", any()) } returns TypedValue.of("hello", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                TypedValue.MIME_TEXT,
                success.context.event.payload
                    ?.mimeType,
            )
        }

    @Test
    fun `should preserve variables and attributes`() =
        runBlocking {
            val event =
                MuseEvent(
                    payload = TypedValue.of("old-payload"),
                    attributes = TypedValue.of(mapOf("key" to "value")),
                    variables = mapOf("existing" to TypedValue.of("keep-me")),
                )
            val ctx =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetPayloadConfig(
                    docName = "test",
                    docId = "test-id",
                    value = "new-payload",
                )
            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("new-payload", any()) } returns TypedValue.of("new-payload", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                "new-payload",
                success.context.event.payload
                    ?.value,
            )
            assertEquals(
                "keep-me",
                success.context.event.variables["existing"]
                    ?.value,
            )
            assertEquals(
                "value",
                (
                    success.context.event.attributes
                        ?.value as? Map<*, *>
                )?.get("key"),
            )
        }

    @Test
    fun `should handle null result from evaluator`() =
        runBlocking {
            val ctx =
                ExecutionContext(
                    event = MuseEvent(payload = TypedValue.of("original")),
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetPayloadConfig(
                    docName = "test",
                    docId = "test-id",
                    value = "#[payload.missing]",
                )
            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("#[payload.missing]", any()) } returns null

            val result = processor.process(ctx)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertNull(success.context.event.payload)
        }

    @Test
    fun `should propagate RuntimeException`() {
        val ctx = context(MuseEvent())
        val config =
            SetPayloadConfig(
                docName = "test",
                docId = "test-id",
                value = "#[bad.expr]",
            )
        val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

        every { expressionEvaluator.evaluate("#[bad.expr]", any()) } throws
            RuntimeException("Evaluation failed")

        val ex =
            assertThrows(RuntimeException::class.java) {
                runBlocking { processor.process(ctx) }
            }
        assertEquals("Evaluation failed", ex.message)
    }

    @Test
    fun `should not modify original context`() =
        runBlocking {
            val originalEvent = MuseEvent(payload = TypedValue.of("original"))
            val ctx =
                ExecutionContext(
                    event = originalEvent,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetPayloadConfig(
                    docName = "test",
                    docId = "test-id",
                    value = "modified",
                )
            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("modified", any()) } returns TypedValue.of("modified-value")

            val originalPayloadBefore = ctx.event.payload?.value

            val result = processor.process(ctx)

            val originalPayloadAfter = ctx.event.payload?.value
            assertEquals(originalPayloadBefore, originalPayloadAfter)

            val success = result as ProcessorResult.Success
            assertEquals(
                "modified-value",
                success.context.event.payload
                    ?.value,
            )
            assertFalse(
                originalPayloadBefore ==
                    success.context.event.payload
                        ?.value,
            )
        }
}

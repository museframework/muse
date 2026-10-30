package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.SetVariableConfig
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

class SetVariableMuseProcessorTest {
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
    fun `should set variable with literal value`() =
        runBlocking {
            val ctx =
                ExecutionContext(
                    event = MuseEvent(),
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "httpStatus",
                    value = "202",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("202", any()) } returns TypedValue.of("202", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "202",
                success.context.event.variables["httpStatus"]
                    ?.value,
            )
        }

    @Test
    fun `should set variable with expression value`() =
        runBlocking {
            val event = MuseEvent(payload = TypedValue.of("the-payload"))
            val ctx =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "captured",
                    value = "#[payload]",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("#[payload]", any()) } returns TypedValue.of("the-payload")

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                "the-payload",
                success.context.event.variables["captured"]
                    ?.value,
            )
        }

    @Test
    fun `should set variable with vars expression`() =
        runBlocking {
            val event = MuseEvent(variables = mapOf("x" to TypedValue.of("42")))
            val ctx =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "y",
                    value = "#[vars.x]",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("#[vars.x]", any()) } returns TypedValue.of("42")

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                "42",
                success.context.event.variables["y"]
                    ?.value,
            )
        }

    @Test
    fun `should set variable with string interpolation`() =
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
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "greeting",
                    value = "Hello #[payload]",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("Hello #[payload]", any()) } returns TypedValue.of("Hello World", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                "Hello World",
                success.context.event.variables["greeting"]
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
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "data",
                    value = "{}",
                    mimeType = "application/json",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("{}", any()) } returns TypedValue.of("{}", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            val variable = success.context.event.variables["data"]
            assertNotNull(variable)
            assertEquals("application/json", variable!!.mimeType)
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
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "data",
                    value = "hello",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("hello", any()) } returns TypedValue.of("hello", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                TypedValue.MIME_TEXT,
                success.context.event.variables["data"]
                    ?.mimeType,
            )
        }

    @Test
    fun `should preserve existing variables`() =
        runBlocking {
            val existing: TypedValue<Any>? = TypedValue.of("keep-me")
            val event = MuseEvent(variables = mapOf("existing" to existing))
            val ctx =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "newVar",
                    value = "new",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("new", any()) } returns TypedValue.of("new", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                "keep-me",
                success.context.event.variables["existing"]
                    ?.value,
            )
            assertEquals(
                "new",
                success.context.event.variables["newVar"]
                    ?.value,
            )
        }

    @Test
    fun `should overwrite same-name variable`() =
        runBlocking {
            val event = MuseEvent(variables = mapOf("v" to TypedValue.of("old")))
            val ctx =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "v",
                    value = "new",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("new", any()) } returns TypedValue.of("new-value", TypedValue.MIME_TEXT)

            val result = processor.process(ctx)

            val success = result as ProcessorResult.Success
            assertEquals(
                "new-value",
                success.context.event.variables["v"]
                    ?.value,
            )
        }

    @Test
    fun `should handle null result from evaluator`() =
        runBlocking {
            val ctx =
                ExecutionContext(
                    event = MuseEvent(),
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "nullVar",
                    value = "#[payload.missing]",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("#[payload.missing]", any()) } returns null

            val result = processor.process(ctx)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertNull(success.context.event.variables["nullVar"])
        }

    @Test
    fun `should propagate RuntimeException`() {
        val ctx = context(MuseEvent())
        val config =
            SetVariableConfig(
                docName = "test",
                docId = "test-id",
                variableName = "fail",
                value = "#[bad.expr]",
            )
        val processor = SetVariableMuseProcessor(config, expressionEvaluator)

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
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "v",
                    value = "new",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("new", any()) } returns TypedValue.of("new-value")

            val result = processor.process(ctx)

            assertTrue(ctx.event.variables.isEmpty())
            val success = result as ProcessorResult.Success
            assertEquals(
                "new-value",
                success.context.event.variables["v"]
                    ?.value,
            )
        }

    @Test
    fun `should always return ProcessorResult Success`() =
        runBlocking {
            val ctx =
                ExecutionContext(
                    event = MuseEvent(),
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                SetVariableConfig(
                    docName = "test",
                    docId = "test-id",
                    variableName = "v",
                    value = "val",
                )
            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            every { expressionEvaluator.evaluate("val", any()) } returns TypedValue.of("result")

            val result = processor.process(ctx)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            assertNotNull((result as ProcessorResult.Success).context)
        }
}

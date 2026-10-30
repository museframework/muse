package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.SetVariableTransformConfig
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveExecutor
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransformMuseProcessorTest {
    private val executor = mockk<DataWeaveExecutor>()
    private val expressionEvaluator = mockk<MuseExpressionEvaluatorImpl>(relaxed = true)

    @Test
    fun `should transform payload with inline expression`() =
        runBlocking {
            val event = MuseEvent(payload = TypedValue.of("original"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setPayload = DwExpression.Inline("payload.name"),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("payload.name", any()) } returns TypedValue.of("John", TypedValue.MIME_JSON)

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "John",
                success.context.event.payload
                    ?.value,
            )
            verify(exactly = 1) { executor.evaluate("payload.name", any()) }
        }

    @Test
    fun `should transform payload with resource expression`() =
        runBlocking {
            val event = MuseEvent(payload = TypedValue.of("original"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setPayload = DwExpression.Resource("/dwl/transform.dwl"),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluateResource("/dwl/transform.dwl", any()) } returns
                TypedValue.of(mapOf("key" to "value"), TypedValue.MIME_JSON)

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "value",
                (
                    success.context.event.payload
                        ?.value as? Map<*, *>
                )?.get("key"),
            )
            verify(exactly = 1) { executor.evaluateResource("/dwl/transform.dwl", any()) }
            verify(exactly = 0) { executor.evaluate(any(), any()) }
        }

    @Test
    fun `should preserve payload when setPayload is null`() =
        runBlocking {
            val originalPayload: TypedValue<Any>? = TypedValue.of("original-payload")
            val event = MuseEvent(payload = originalPayload)
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                )
            val processor = TransformMuseProcessor(config, executor)

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(originalPayload, success.context.event.payload)
            verify(exactly = 0) { executor.evaluate(any(), any()) }
            verify(exactly = 0) { executor.evaluateResource(any(), any()) }
        }

    @Test
    fun `should transform attributes with inline expression`() =
        runBlocking {
            val event = MuseEvent(payload = TypedValue.of("test"), attributes = TypedValue.of(mapOf("old" to "value")))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setAttributes = DwExpression.Inline("attributes.statusCode"),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("attributes.statusCode", any()) } returns
                TypedValue.of(
                    200 as Any,
                    TypedValue.MIME_JAVA,
                )

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                200,
                success.context.event.attributes
                    ?.value,
            )
            assertEquals(
                "test",
                success.context.event.payload
                    ?.value,
            )
        }

    @Test
    fun `should preserve attributes when setAttributes is null`() =
        runBlocking {
            val originalAttributes = TypedValue.of(mapOf("statusCode" to 200) as Any)
            val event = MuseEvent(attributes = originalAttributes)
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                )
            val processor = TransformMuseProcessor(config, executor)

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(originalAttributes, success.context.event.attributes)
        }

    @Test
    fun `should set single variable`() =
        runBlocking {
            val event = MuseEvent()
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setVariables = listOf(SetVariableTransformConfig("myVar", DwExpression.Inline("payload.value"))),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("payload.value", any()) } returns TypedValue.of("hello")

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "hello",
                success.context.event.variables["myVar"]
                    ?.value,
            )
        }

    @Test
    fun `should set multiple variables`() =
        runBlocking {
            val event = MuseEvent()
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setVariables =
                        listOf(
                            SetVariableTransformConfig("var1", DwExpression.Inline("expr1")),
                            SetVariableTransformConfig("var2", DwExpression.Resource("/dwl/file.dwl")),
                        ),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("expr1", any()) } returns TypedValue.of("value1")
            every { executor.evaluateResource("/dwl/file.dwl", any()) } returns TypedValue.of("value2")

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "value1",
                success.context.event.variables["var1"]
                    ?.value,
            )
            assertEquals(
                "value2",
                success.context.event.variables["var2"]
                    ?.value,
            )
            verify(exactly = 1) { executor.evaluate("expr1", any()) }
            verify(exactly = 1) { executor.evaluateResource("/dwl/file.dwl", any()) }
        }

    @Test
    fun `should preserve existing variables when adding new ones`() =
        runBlocking {
            val existingVar: TypedValue<Any>? = TypedValue.of("keep-me")
            val event = MuseEvent(variables = mapOf("existing" to existingVar))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setVariables = listOf(SetVariableTransformConfig("newVar", DwExpression.Inline("expr"))),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("expr", any()) } returns TypedValue.of("new-value")

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "keep-me",
                success.context.event.variables["existing"]
                    ?.value,
            )
            assertEquals(
                "new-value",
                success.context.event.variables["newVar"]
                    ?.value,
            )
        }

    @Test
    fun `should handle empty setVariables list`() =
        runBlocking {
            val existingVar: TypedValue<Any>? = TypedValue.of("keep")
            val event = MuseEvent(variables = mapOf("existing" to existingVar))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setVariables = emptyList(),
                )
            val processor = TransformMuseProcessor(config, executor)

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "keep",
                success.context.event.variables["existing"]
                    ?.value,
            )
            verify(exactly = 0) { executor.evaluate(any(), any()) }
        }

    @Test
    fun `should handle combined payload, attributes, and variables`() =
        runBlocking {
            val event = MuseEvent()
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setPayload = DwExpression.Inline("payloadExpr"),
                    setAttributes = DwExpression.Inline("attrExpr"),
                    setVariables = listOf(SetVariableTransformConfig("v1", DwExpression.Inline("varExpr"))),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("payloadExpr", any()) } returns TypedValue.of("newPayload")
            every { executor.evaluate("attrExpr", any()) } returns TypedValue.of("newAttr")
            every { executor.evaluate("varExpr", any()) } returns TypedValue.of("newVar")

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "newPayload",
                success.context.event.payload
                    ?.value,
            )
            assertEquals(
                "newAttr",
                success.context.event.attributes
                    ?.value,
            )
            assertEquals(
                "newVar",
                success.context.event.variables["v1"]
                    ?.value,
            )
            verify(exactly = 3) { executor.evaluate(any(), any()) }
        }

    @Test
    fun `should handle null result from executor for payload`() =
        runBlocking {
            val event = MuseEvent(payload = TypedValue.of("original"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setPayload = DwExpression.Inline("payload.missing"),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("payload.missing", any()) } returns null as TypedValue<Any>?

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            verify { executor.evaluate("payload.missing", any()) }
            assertEquals(
                "original",
                success.context.event.payload
                    ?.value,
            )
        }

    @Test
    fun `should handle null result from executor for variable`() =
        runBlocking {
            val event = MuseEvent()
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setVariables = listOf(SetVariableTransformConfig("nullVar", DwExpression.Inline("expr"))),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("expr", any()) } returns null

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertNull(
                success.context.event.variables["nullVar"]
                    ?.value,
            )
        }

    @Test
    fun `should always return ProcessorResult Success`() =
        runBlocking {
            val event = MuseEvent()
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setPayload = DwExpression.Inline("payload"),
                    setAttributes = DwExpression.Inline("attributes"),
                    setVariables = listOf(SetVariableTransformConfig("var", DwExpression.Inline("vars.var"))),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate(any(), any()) } returns TypedValue.of("result")

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertNotNull(success.context)
        }

    @Test
    fun `should not modify original context`() =
        runBlocking {
            val originalEvent = MuseEvent(payload = TypedValue.of("original"))
            val context =
                ExecutionContext(
                    event = originalEvent,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setPayload = DwExpression.Inline("expr"),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("expr", any()) } returns TypedValue.of("modified")

            val originalPayloadBefore = context.event.payload?.value

            val result = processor.process(context)

            val originalPayloadAfter = context.event.payload?.value
            assertEquals(originalPayloadBefore, originalPayloadAfter)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(
                "modified",
                success.context.event.payload
                    ?.value,
            )
            assertFalse(
                originalPayloadBefore ==
                    success.context.event.payload
                        ?.value,
            )
        }

    @Test
    fun `should propagate RuntimeException from payload evaluation`() {
        val event = MuseEvent(payload = TypedValue.of("original"))
        val context =
            runBlocking {
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            }
        val config =
            TransformConfig(
                docName = "test",
                docId = "test-id",
                setPayload = DwExpression.Inline("bad.expression"),
            )
        val processor = TransformMuseProcessor(config, executor)

        every { executor.evaluate("bad.expression", any()) } throws
            RuntimeException("Evaluation failed: cannot resolve 'bad.expression'")

        val ex =
            assertThrows(RuntimeException::class.java) {
                runBlocking { processor.process(context) }
            }
        assertEquals("Evaluation failed: cannot resolve 'bad.expression'", ex.message)
    }

    @Test
    fun `should propagate RuntimeException from attributes evaluation`() {
        val event = MuseEvent(payload = TypedValue.of("test"))
        val context =
            runBlocking {
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            }
        val config =
            TransformConfig(
                docName = "test",
                docId = "test-id",
                setAttributes = DwExpression.Inline("invalid.attr"),
            )
        val processor = TransformMuseProcessor(config, executor)

        every { executor.evaluate("invalid.attr", any()) } throws
            RuntimeException("Attribute evaluation failed")

        val ex =
            assertThrows(RuntimeException::class.java) {
                runBlocking { processor.process(context) }
            }
        assertEquals("Attribute evaluation failed", ex.message)
    }

    @Test
    fun `should propagate RuntimeException from variable evaluation`() {
        val event = MuseEvent()
        val context =
            runBlocking {
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            }
        val config =
            TransformConfig(
                docName = "test",
                docId = "test-id",
                setVariables =
                    listOf(
                        SetVariableTransformConfig("failVar", DwExpression.Inline("broken.expr")),
                    ),
            )
        val processor = TransformMuseProcessor(config, executor)

        every { executor.evaluate("broken.expr", any()) } throws
            RuntimeException("Variable evaluation failed")

        val ex =
            assertThrows(RuntimeException::class.java) {
                runBlocking { processor.process(context) }
            }
        assertEquals("Variable evaluation failed", ex.message)
    }

    @Test
    fun `should propagate RuntimeException from resource evaluation`() {
        val event = MuseEvent(payload = TypedValue.of("data"))
        val context =
            runBlocking {
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            }
        val config =
            TransformConfig(
                docName = "test",
                docId = "test-id",
                setPayload = DwExpression.Resource("/dwl/missing.dwl"),
            )
        val processor = TransformMuseProcessor(config, executor)

        every { executor.evaluateResource("/dwl/missing.dwl", any()) } throws
            RuntimeException("Resource not found: /dwl/missing.dwl")

        val ex =
            assertThrows(RuntimeException::class.java) {
                runBlocking { processor.process(context) }
            }
        assertTrue(ex.message!!.contains("/dwl/missing.dwl"))
    }

    @Test
    fun `should propagate exception on second variable leaving no partial result`() {
        val event = MuseEvent()
        val context =
            runBlocking {
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            }
        val config =
            TransformConfig(
                docName = "test",
                docId = "test-id",
                setVariables =
                    listOf(
                        SetVariableTransformConfig("okVar", DwExpression.Inline("good.expr")),
                        SetVariableTransformConfig("failVar", DwExpression.Inline("bad.expr")),
                    ),
            )
        val processor = TransformMuseProcessor(config, executor)

        every { executor.evaluate("good.expr", any()) } returns TypedValue.of("success")
        every { executor.evaluate("bad.expr", any()) } throws
            RuntimeException("Second variable evaluation failed")

        assertThrows(RuntimeException::class.java) {
            runBlocking { processor.process(context) }
        }
        verify(exactly = 1) { executor.evaluate("good.expr", any()) }
        verify(exactly = 1) { executor.evaluate("bad.expr", any()) }
    }

    @Test
    fun `should propagate exception from payload before evaluating attributes or variables`() {
        val event = MuseEvent()
        val context =
            runBlocking {
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            }
        val config =
            TransformConfig(
                docName = "test",
                docId = "test-id",
                setPayload = DwExpression.Inline("fail.early"),
                setAttributes = DwExpression.Inline("never.reached"),
                setVariables =
                    listOf(
                        SetVariableTransformConfig("neverSet", DwExpression.Inline("also.unreached")),
                    ),
            )
        val processor = TransformMuseProcessor(config, executor)

        every { executor.evaluate("fail.early", any()) } throws
            RuntimeException("Payload blew up")

        assertThrows(RuntimeException::class.java) {
            runBlocking { processor.process(context) }
        }
        verify(exactly = 1) { executor.evaluate("fail.early", any()) }
        verify(exactly = 0) { executor.evaluate("never.reached", any()) }
        verify(exactly = 0) { executor.evaluate("also.unreached", any()) }
    }

    @Test
    fun `should handle null result from executor for attributes`() =
        runBlocking {
            val originalAttributes: TypedValue<Any>? = TypedValue.of(mapOf("key" to "value"))
            val event = MuseEvent(attributes = originalAttributes)
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            val config =
                TransformConfig(
                    docName = "test",
                    docId = "test-id",
                    setAttributes = DwExpression.Inline("returns.null"),
                )
            val processor = TransformMuseProcessor(config, executor)

            every { executor.evaluate("returns.null", any()) } returns null

            val result = processor.process(context)

            assertInstanceOf(ProcessorResult.Success::class.java, result)
            val success = result as ProcessorResult.Success
            assertEquals(originalAttributes, success.context.event.attributes)
        }

    @Test
    fun `should propagate IllegalArgumentException from executor`() {
        val event = MuseEvent()
        val context =
            runBlocking {
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = this,
                    expressionEvaluator = expressionEvaluator,
                )
            }
        val config =
            TransformConfig(
                docName = "test",
                docId = "test-id",
                setPayload = DwExpression.Inline(""),
            )
        val processor = TransformMuseProcessor(config, executor)

        every { executor.evaluate("", any()) } throws
            IllegalArgumentException("Expression must not be empty")

        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { processor.process(context) }
            }
        assertEquals("Expression must not be empty", ex.message)
    }
}

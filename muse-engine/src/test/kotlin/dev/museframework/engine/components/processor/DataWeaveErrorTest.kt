package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.*

@DisplayName("DataWeave Error Handling Tests")
class DataWeaveErrorTest {
    private val dataWeaveExecutor = MuseDataWeaveExecutor()
    private val expressionEvaluator = MuseExpressionEvaluatorImpl(dataWeaveExecutor, Properties())
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun createExecutionContext(event: MuseEvent = MuseEvent()): ExecutionContext =
        ExecutionContext(
            event = event,
            errorHandler = null,
            coroutineScope = coroutineScope,
            expressionEvaluator = expressionEvaluator,
        )

    @Test
    @DisplayName("SetPayload with invalid expression should return MULE:EXPRESSION error")
    fun `SetPayload with invalid expression returns MULE_EXPRESSION error`() =
        runTest {
            val config =
                SetPayloadConfig(
                    docName = "setPayload",
                    docId = "test-doc-id",
                    sourceFile = "TestFlow.kt",
                    sourceLine = 10,
                    sourceClass = "com.example.TestFlow",
                    value = "#[unknownFunctionThatDoesNotExist()]",
                )

            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            val context = createExecutionContext()
            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failure = result as ProcessorResult.Failure
            assertEquals(ErrorType.Mule.EXPRESSION, failure.error.errorType)
            assertTrue(failure.error.description.contains("Expression evaluation failed"))
        }

    @Test
    @DisplayName("SetPayload error should include source info")
    fun `SetPayload error includes source info`() =
        runTest {
            val config =
                SetPayloadConfig(
                    docName = "setPayload",
                    docId = "test-doc-id",
                    sourceFile = "TestFlow.kt",
                    sourceLine = 42,
                    sourceClass = "com.example.TestFlow",
                    value = "#[unknownFunctionThatDoesNotExist()]",
                )

            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            val context = createExecutionContext()
            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failure = result as ProcessorResult.Failure
            assertEquals("TestFlow.kt", failure.error.sourceFile)
            assertEquals(42, failure.error.sourceLine)
            assertEquals("com.example.TestFlow", failure.error.sourceClass)
        }

    @Test
    @DisplayName("SetVariable with invalid expression should return MULE:EXPRESSION error")
    fun `SetVariable with invalid expression returns MULE_EXPRESSION error`() =
        runTest {
            val config =
                SetVariableConfig(
                    docName = "setVariable",
                    docId = "test-doc-id",
                    sourceFile = "TestFlow.kt",
                    sourceLine = 20,
                    sourceClass = "com.example.TestFlow",
                    variableName = "testVar",
                    value = "#[unknownFunctionThatDoesNotExist()]",
                )

            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            val context = createExecutionContext()
            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failure = result as ProcessorResult.Failure
            assertEquals(ErrorType.Mule.EXPRESSION, failure.error.errorType)
            assertTrue(failure.error.description.contains("Expression evaluation failed"))
        }

    @Test
    @DisplayName("SetVariable error should include source info")
    fun `SetVariable error includes source info`() =
        runTest {
            val config =
                SetVariableConfig(
                    docName = "setVariable",
                    docId = "test-doc-id",
                    sourceFile = "VariableFlow.kt",
                    sourceLine = 55,
                    sourceClass = "com.example.VariableFlow",
                    variableName = "testVar",
                    value = "#[unknownFunctionThatDoesNotExist()]",
                )

            val processor = SetVariableMuseProcessor(config, expressionEvaluator)

            val context = createExecutionContext()
            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failure = result as ProcessorResult.Failure
            assertEquals("VariableFlow.kt", failure.error.sourceFile)
            assertEquals(55, failure.error.sourceLine)
            assertEquals("com.example.VariableFlow", failure.error.sourceClass)
        }

    @Test
    @DisplayName("Transform with invalid expression should return MULE:EXPRESSION error")
    fun `Transform with invalid expression returns MULE_EXPRESSION error`() =
        runTest {
            val config =
                TransformConfig(
                    docName = "transform",
                    docId = "test-doc-id",
                    sourceFile = "TestFlow.kt",
                    sourceLine = 30,
                    sourceClass = "com.example.TestFlow",
                    setPayload = DwExpression.Inline("%dw 2.0\n--- invalidFunction()"),
                    setAttributes = null,
                    setVariables = emptyList(),
                )

            val processor = TransformMuseProcessor(config, dataWeaveExecutor)

            val context = createExecutionContext()
            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failure = result as ProcessorResult.Failure
            assertEquals(ErrorType.Mule.EXPRESSION, failure.error.errorType)
            assertTrue(failure.error.description.contains("Expression evaluation failed"))
        }

    @Test
    @DisplayName("Transform error should include source info")
    fun `Transform error includes source info`() =
        runTest {
            val config =
                TransformConfig(
                    docName = "transform",
                    docId = "test-doc-id",
                    sourceFile = "TransformFlow.kt",
                    sourceLine = 99,
                    sourceClass = "com.example.TransformFlow",
                    setPayload = DwExpression.Inline("%dw 2.0\n--- invalidFunction()"),
                    setAttributes = null,
                    setVariables = emptyList(),
                )

            val processor = TransformMuseProcessor(config, dataWeaveExecutor)

            val context = createExecutionContext()
            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failure = result as ProcessorResult.Failure
            assertEquals("TransformFlow.kt", failure.error.sourceFile)
            assertEquals(99, failure.error.sourceLine)
            assertEquals("com.example.TransformFlow", failure.error.sourceClass)
        }

    @Test
    @DisplayName("Valid expression should succeed without error")
    fun `Valid expression succeeds without error`() =
        runTest {
            val config =
                SetPayloadConfig(
                    docName = "setPayload",
                    docId = "test-doc-id",
                    sourceFile = "TestFlow.kt",
                    sourceLine = 10,
                    sourceClass = "com.example.TestFlow",
                    value = "'Hello World'",
                )

            val processor = SetPayloadMuseProcessor(config, expressionEvaluator)

            val context = createExecutionContext()
            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Success)
        }
}

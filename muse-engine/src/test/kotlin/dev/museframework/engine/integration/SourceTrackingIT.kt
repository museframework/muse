package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DisplayName("Source Tracking Integration Tests")
class SourceTrackingIT {
    @Test
    @DisplayName("Error should include source file and line from component")
    fun `error includes source file and line from component`() =
        runTest {
            var capturedError: MuseError? = null
            val flow =
                flow("source-tracking-flow") {
                    setPayload {
                        docName = "Failing SetPayload"
                        sourceFile = "TestFlow.kt"
                        sourceLine = 42
                        sourceClass = "com.example.TestFlow"
                        value = "#[unknownFunctionThatDoesNotExist()]"
                    }
                    errorHandler {
                        onErrorContinue(ErrorType.Mule.EXPRESSION) {
                            processor(CaptureConfig(docName = "Capture Error", docId = "capture-error-001"))
                        }
                    }
                }
            val factory =
                MuseComponentFactory().apply {
                    registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
                    registerProcessor(CaptureConfig::class) { component, _ ->
                        CaptureMuseProcessor(component) { context ->
                            capturedError = context.event.error
                            ProcessorResult.Success(context)
                        }
                    }
                }

            EngineFlowHarness(this, flow, factory).execute()

            val error = assertNotNull(capturedError)
            assertEquals(ErrorType.Mule.EXPRESSION, error.errorType)
            assertEquals("TestFlow.kt", error.sourceFile)
            assertEquals(42, error.sourceLine)
            assertEquals("com.example.TestFlow", error.sourceClass)
        }

    @Test
    @DisplayName("SetPayloadConfig should capture source info")
    fun `setPayloadConfig captures source info`() {
        val config =
            SetPayloadConfig(
                docName = "Test",
                docId = "test-001",
                sourceFile = "TestFlow.kt",
                sourceLine = 42,
                sourceClass = "com.example.TestFlow",
                value = "test",
            )

        assertEquals("TestFlow.kt", config.sourceFile)
        assertEquals(42, config.sourceLine)
        assertEquals("com.example.TestFlow", config.sourceClass)
    }
}

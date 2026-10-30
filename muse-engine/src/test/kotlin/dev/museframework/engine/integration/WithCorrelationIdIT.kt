package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.builder.withCorrelationId
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.flowRef
import dev.museframework.dsl.dsl.subFlow
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.execution.policy.flowRefPolicyDeclaration
import dev.museframework.engine.execution.policy.withCorrelationIdPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WithCorrelationIdIT {
    @Test
    fun `should change correlation ID inside scope and restore it after`() =
        runTest {
            var insideCorrelationId: String? = null
            val flow =
                flow("correlation-test-flow") {
                    withCorrelationId {
                        correlationId = "#[correlationId ++ '-SCOPE']"
                        processor(CorrelationCaptureConfig(docId = "inside"))
                    }
                }
            val input = MuseEvent(correlationId = ORIGINAL_CORRELATION_ID)
            val factory = factory { _, context ->
                insideCorrelationId = context.event.correlationId
                ProcessorResult.Success(context)
            }

            val result = EngineFlowHarness(this, flow, factory).execute(input)

            assertEquals("$ORIGINAL_CORRELATION_ID-SCOPE", insideCorrelationId)
            assertEquals(ORIGINAL_CORRELATION_ID, result.correlationId)
        }

    @Test
    fun `should set literal correlation ID inside scope`() =
        runTest {
            var capturedCorrelationId: String? = null
            val flow =
                flow("literal-correlation-flow") {
                    withCorrelationId {
                        correlationId = "my-fixed-correlation-id"
                        processor(CorrelationCaptureConfig(docId = "capture"))
                    }
                }
            val factory = factory { _, context ->
                capturedCorrelationId = context.event.correlationId
                ProcessorResult.Success(context)
            }

            EngineFlowHarness(this, flow, factory)
                .execute(MuseEvent(correlationId = ORIGINAL_CORRELATION_ID))

            assertEquals("my-fixed-correlation-id", capturedCorrelationId)
        }

    @Test
    fun `should propagate scope correlation ID into referenced sub-flow`() =
        runTest {
            var capturedCorrelationId: String? = null
            val referencedFlow =
                subFlow("sub-flow") {
                    processor(CorrelationCaptureConfig(docId = "capture-in-subflow"))
                }
            val mainFlow =
                flow("main-flow-with-subflow") {
                    withCorrelationId {
                        correlationId = "#[correlationId ++ '-SCOPE']"
                        flowRef(referencedFlow) {}
                    }
                }
            val factory = factory { _, context ->
                capturedCorrelationId = context.event.correlationId
                ProcessorResult.Success(context)
            }

            EngineFlowHarness(
                this,
                mainFlow,
                factory,
                additionalFlows = listOf(referencedFlow),
            ).execute(MuseEvent(correlationId = ORIGINAL_CORRELATION_ID))

            assertEquals("$ORIGINAL_CORRELATION_ID-SCOPE", capturedCorrelationId)
        }

    @Test
    fun `should use original correlation ID in outer error handler`() =
        runTest {
            var errorHandlerCorrelationId: String? = null
            val flow =
                flow("error-outside-scope-flow") {
                    withCorrelationId {
                        correlationId = "#[correlationId ++ '-SCOPE']"
                        processor(CorrelationCaptureConfig(docId = "raise-error"))
                    }
                    errorHandler {
                        onErrorContinue(ErrorType.Mule.ANY) {
                            processor(CorrelationCaptureConfig(docId = "capture-error-handler"))
                        }
                    }
                }
            val factory =
                factory { component, context ->
                    when (component.docId) {
                        "capture-error-handler" -> {
                            errorHandlerCorrelationId = context.event.correlationId
                            ProcessorResult.Success(context)
                        }

                        else -> failure(context)
                    }
                }

            val result =
                EngineFlowHarness(this, flow, factory)
                    .execute(MuseEvent(correlationId = ORIGINAL_CORRELATION_ID))

            assertEquals(ORIGINAL_CORRELATION_ID, errorHandlerCorrelationId)
            assertNull(result.error)
        }

    @Test
    fun `should use scope correlation ID in referenced flow error handler`() =
        runTest {
            var scopeCorrelationId: String? = null
            var errorHandlerCorrelationId: String? = null
            val referencedFlow =
                flow("inner-error-flow") {
                    processor(CorrelationCaptureConfig(docId = "capture-scope"))
                    processor(CorrelationCaptureConfig(docId = "raise-error"))
                    errorHandler {
                        onErrorContinue(ErrorType.Mule.ANY) {
                            processor(CorrelationCaptureConfig(docId = "capture-error-handler"))
                        }
                    }
                }
            val mainFlow =
                flow("error-inside-scope-flow") {
                    withCorrelationId {
                        correlationId = "#[correlationId ++ '-SCOPE']"
                        flowRef(referencedFlow) {}
                    }
                }
            val factory =
                factory { component, context ->
                    when (component.docId) {
                        "capture-scope" -> {
                            scopeCorrelationId = context.event.correlationId
                            ProcessorResult.Success(context)
                        }

                        "capture-error-handler" -> {
                            errorHandlerCorrelationId = context.event.correlationId
                            ProcessorResult.Success(context)
                        }

                        else -> failure(context)
                    }
                }

            EngineFlowHarness(
                this,
                mainFlow,
                factory,
                additionalFlows = listOf(referencedFlow),
            ).execute(MuseEvent(correlationId = ORIGINAL_CORRELATION_ID))

            assertEquals("$ORIGINAL_CORRELATION_ID-SCOPE", scopeCorrelationId)
            assertEquals(scopeCorrelationId, errorHandlerCorrelationId)
        }

    private fun factory(
        capture: suspend (CorrelationCaptureConfig, ExecutionContext) -> ProcessorResult,
    ): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(CorrelationCaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context -> capture(component, context) }
            }
            registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
            registerPolicyRouter(WithCorrelationIdConfig::class, withCorrelationIdPolicyDeclaration)
        }

    private fun failure(context: ExecutionContext): ProcessorResult.Failure =
        ProcessorResult.Failure(
            error =
                MuseError(
                    description = "test error",
                    detailedDescription = "test error",
                    errorType = ErrorType.Mule.ANY,
                    cause = RuntimeException("test error"),
                ),
            context = context,
        )

    private companion object {
        const val ORIGINAL_CORRELATION_ID = "original-correlation-id"
    }
}

data class CorrelationCaptureConfig(
    override val docName: String = "Correlation Capture",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Processor

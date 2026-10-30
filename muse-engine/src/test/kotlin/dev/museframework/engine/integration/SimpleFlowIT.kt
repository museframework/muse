package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ChoiceRoute
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.LogLevel
import dev.museframework.dsl.api.config.LoggerConfig
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.flowRef
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.MuseBootstrapException
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.engine.components.processor.loggerProcessorBuilder
import dev.museframework.engine.execution.policy.flowRefPolicyDeclaration
import dev.museframework.engine.components.source.schedulerSourceBuilder
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleFlowIT {
    @Test
    fun `should process events from scheduler through logger`() =
        runBlocking {
            val processedEvents = CopyOnWriteArrayList<MuseEvent>()
            val latch = CountDownLatch(3)
            val factory = schedulerFactory(processedEvents, latch)
            val flow =
                MuseFlow.Flow(
                    name = "test-flow",
                    docId = "test-flow-001",
                    source =
                        SchedulerConfig(
                            docName = "Test Scheduler",
                            docId = "test-scheduler-001",
                            schedulingStrategy = FixedFrequencyStrategy(frequency = "100"),
                        ),
                    pipeline =
                        listOf(
                            LoggerConfig(
                                docName = "Test Logger",
                                docId = "test-logger-001",
                                level = LogLevel.INFO,
                                message = "#[payload default \"logger\"]",
                            ),
                            CaptureConfig(
                                docName = "Test Capture",
                                docId = "test-capture-001",
                            ),
                        ),
                )
            val application =
                bootstrap(
                    MuseApplicationConfig(
                        flows = listOf(flow),
                        factory = factory,
                    ),
                )

            application.init()
            val completed =
                try {
                    application.start()
                    latch.await(5, TimeUnit.SECONDS)
                } finally {
                    application.stop()
                }

            assertTrue(completed, "At least three events should complete within five seconds")
            assertTrue(processedEvents.size >= 3)
            processedEvents.forEach { event -> assertNull(event.payload?.value) }
        }

    @Test
    fun `should process events from scheduler through logger using dsl`() =
        runBlocking {
            val processedEvents = CopyOnWriteArrayList<MuseEvent>()
            val latch = CountDownLatch(3)
            val factory = schedulerFactory(processedEvents, latch)
            val flow =
                flow("test-flow-dsl-001") {
                    scheduler {
                        docId = "test-scheduler-dsl-001"
                        docName = "Test Scheduler"
                        fixedFrequency {
                            frequency = "100"
                        }
                    }
                    logger {
                        docId = "test-logger-dsl-001"
                        docName = "Test Logger"
                        level = LogLevel.INFO
                        message = "Event received: #[payload]"
                    }
                    processor(
                        CaptureConfig(
                            docName = "Test Capture",
                            docId = "test-capture-dsl-001",
                        ),
                    )
                }
            val application =
                bootstrap(
                    MuseApplicationConfig(
                        flows = listOf(flow),
                        factory = factory,
                    ),
                )

            application.init()
            val completed =
                try {
                    application.start()
                    latch.await(5, TimeUnit.SECONDS)
                } finally {
                    application.stop()
                }

            assertTrue(completed, "At least three DSL events should complete within five seconds")
            assertTrue(processedEvents.size >= 3)
            processedEvents.forEach { event -> assertNull(event.payload?.value) }
        }

    @Test
    fun `should execute flow reference and return to main flow`() =
        runTest {
            val executionOrder = mutableListOf<String>()
            val subFlow =
                MuseFlow.SubFlow(
                    name = "sub-flow",
                    docId = "sub-flow-001",
                    pipeline =
                        listOf(
                            CaptureConfig(
                                docName = "SubFlow Processor",
                                docId = "subflow-processor-001",
                            ),
                        ),
                )
            val mainFlow =
                flow("main-flow") {
                    processor(CaptureConfig(docName = "Main Processor", docId = "main-processor-001"))
                    flowRef(subFlow) {
                        docName = "Flow Ref"
                    }
                    processor(CaptureConfig(docName = "After Ref Processor", docId = "after-ref-processor-001"))
                }
            val factory =
                flowRefFactory { component, context ->
                    executionOrder += component.docName
                    ProcessorResult.Success(context)
                }

            EngineFlowHarness(
                this,
                mainFlow,
                factory,
                additionalFlows = listOf(subFlow),
            ).execute()

            assertEquals(
                listOf("Main Processor", "SubFlow Processor", "After Ref Processor"),
                executionOrder,
            )
        }

    @Test
    fun `should save flow reference result in target variable`() =
        runTest {
            val subFlow =
                MuseFlow.SubFlow(
                    name = "sub-flow",
                    docId = "sub-flow-001",
                    pipeline =
                        listOf(
                            CaptureConfig(
                                docName = "SubFlow Result",
                                docId = "subflow-result",
                            ),
                        ),
                )
            val mainFlow =
                MuseFlow.Flow(
                    name = "main-flow",
                    docId = "main-flow-001",
                    source = null,
                    pipeline =
                        listOf(
                            FlowRefConfig(
                                docName = "Flow Ref",
                                docId = "flow-ref-001",
                                ref = FlowRef.ByReference(subFlow),
                                targetConfig = TargetConfig.of("subFlowResult"),
                            ),
                        ),
                )
            val factory =
                flowRefFactory { _, context ->
                    ProcessorResult.Success(
                        context.withEvent(
                            context.event.copy(payload = TypedValue.of("sub-flow-result")),
                        ),
                    )
                }
            val input = MuseEvent(payload = TypedValue.of("main-flow-input"))

            val result =
                EngineFlowHarness(
                    this,
                    mainFlow,
                    factory,
                    additionalFlows = listOf(subFlow),
                ).execute(input)

            assertEquals("main-flow-input", result.payload?.value)
            assertEquals("sub-flow-result", result.variables["subFlowResult"]?.value)
        }

    @Test
    fun `should throw MuseBootstrapException when flow ref by name points to missing flow`() {
        val factory = MuseComponentFactory()
        factory.registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
        val mainFlow =
            MuseFlow.Flow(
                name = "main-flow",
                docId = "main-flow-001",
                source = null,
                pipeline =
                    listOf(
                        FlowRefConfig(
                            docName = "Flow Ref",
                            docId = "flow-ref-001",
                            ref = FlowRef.ByName("non-existent-flow"),
                        ),
                    ),
            )

        val exception =
            assertThrows<MuseBootstrapException> {
                bootstrap(
                    MuseApplicationConfig(
                        flows = listOf(mainFlow),
                        factory = factory,
                    ),
                )
            }

        assertEquals(1, exception.errors.size)
        assertTrue(exception.errors.single().message.contains("non-existent-flow"))
    }

    @Test
    fun `should reject a missing flow ref nested in a choice during bootstrap`() {
        val mainFlow =
            MuseFlow.Flow(
                name = "main-flow",
                docId = "main-flow-001",
                source = null,
                pipeline =
                    listOf(
                        ChoiceConfig(
                            docName = "Choice",
                            docId = "choice-001",
                            whenRoutes =
                                listOf(
                                    ChoiceRoute(
                                        expression = "#[true]",
                                        pipeline =
                                            listOf(
                                                FlowRefConfig(
                                                    docName = "Nested Flow Ref",
                                                    docId = "nested-flow-ref-001",
                                                    ref = FlowRef.ByName("missing-nested-flow"),
                                                ),
                                            ),
                                    ),
                                ),
                            otherwise = null,
                        ),
                    ),
            )

        val exception =
            assertThrows<MuseBootstrapException> {
                bootstrap(
                    MuseApplicationConfig(
                        flows = listOf(mainFlow),
                        factory = MuseComponentFactory(),
                    ),
                )
            }

        assertEquals(1, exception.errors.size)
        assertTrue(exception.errors.single().message.contains("missing-nested-flow"))
    }

    @Test
    fun `should not execute remaining subflow processors after propagated error`() =
        runTest {
            var failingProcessorExecuted = false
            var afterFailureProcessorExecuted = false
            var afterRefProcessorExecuted = false
            val subFlow =
                MuseFlow.SubFlow(
                    name = "sub-flow",
                    docId = "sub-flow-001",
                    pipeline =
                        listOf(
                            CaptureConfig(
                                docName = "Failing SubFlow Processor",
                                docId = "failing-subflow-processor-001",
                            ),
                            CaptureConfig(
                                docName = "After Failure SubFlow Processor",
                                docId = "after-failure-subflow-processor-001",
                            ),
                        ),
                )
            val mainFlow =
                flow("main-flow") {
                    flowRef(subFlow) {
                        docName = "Flow Ref"
                    }
                    processor(CaptureConfig(docName = "After Ref Processor", docId = "after-ref-processor-001"))
                }
            val factory =
                flowRefFactory { component, context ->
                    when (component.docId) {
                        "failing-subflow-processor-001" -> {
                            failingProcessorExecuted = true
                            ProcessorResult.Failure(
                                error =
                                    MuseError(
                                        description = "Subflow failed",
                                        detailedDescription = "Subflow failed",
                                        errorType = ErrorType.Mule.UNKNOWN,
                                        cause = RuntimeException("subflow failed"),
                                    ),
                                context = context,
                            )
                        }

                        "after-failure-subflow-processor-001" -> {
                            afterFailureProcessorExecuted = true
                            ProcessorResult.Success(context)
                        }

                        else -> {
                            afterRefProcessorExecuted = true
                            ProcessorResult.Success(context)
                        }
                    }
                }

            val result =
                EngineFlowHarness(
                    this,
                    mainFlow,
                    factory,
                    additionalFlows = listOf(subFlow),
                ).execute()

            assertTrue(failingProcessorExecuted)
            assertFalse(afterFailureProcessorExecuted)
            assertFalse(afterRefProcessorExecuted)
            assertEquals(ErrorType.Mule.UNKNOWN, result.error?.errorType)
        }

    private fun schedulerFactory(
        processedEvents: MutableList<MuseEvent>,
        latch: CountDownLatch,
    ): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerSource(SchedulerConfig::class, schedulerSourceBuilder)
            registerProcessor(LoggerConfig::class, loggerProcessorBuilder)
            registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context ->
                    processedEvents += context.event
                    latch.countDown()
                    ProcessorResult.Success(context)
                }
            }
        }

    private fun flowRefFactory(
        capture: suspend (CaptureConfig, ExecutionContext) -> ProcessorResult,
    ): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
            registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context -> capture(component, context) }
            }
        }
}

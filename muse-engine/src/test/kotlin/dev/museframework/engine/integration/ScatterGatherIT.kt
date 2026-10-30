package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.raiseError
import dev.museframework.dsl.dsl.scatterGather
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.raiseErrorProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.scatterGatherPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScatterGatherIT {
    @Test
    fun `should execute multiple routes and aggregate results into payload`() =
        runTest {
            val flow =
                flow("scatter-gather-basic") {
                    scatterGather {
                        docName = "Scatter Gather"
                        route {
                            setPayload {
                                docName = "Route 1 Payload"
                                value = "result-1"
                            }
                        }
                        route {
                            setPayload {
                                docName = "Route 2 Payload"
                                value = "result-2"
                            }
                        }
                        route {
                            setPayload {
                                docName = "Route 3 Payload"
                                value = "result-3"
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals(listOf<Any?>("result-1", "result-2", "result-3"), aggregatedPayloads(result))
        }

    @Test
    fun `should store scatter-gather results in target variable when configured`() =
        runTest {
            val flow =
                flow("scatter-gather-target") {
                    setPayload {
                        docName = "Original Payload"
                        value = "original"
                    }
                    scatterGather {
                        docName = "Scatter Gather With Target"
                        target = "sgResults"
                        route {
                            setPayload {
                                docName = "Route 1"
                                value = "r1"
                            }
                        }
                        route {
                            setPayload {
                                docName = "Route 2"
                                value = "r2"
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("original", result.payload?.value)
            val target = assertNotNull(result.variables["sgResults"])
            assertEquals(listOf<Any?>("r1", "r2"), payloads(assertIs<List<*>>(target.value)))
        }

    @Test
    fun `should preserve variables set before scatter-gather`() =
        runTest {
            val flow =
                flow("scatter-gather-vars") {
                    setVariable("before") {
                        docName = "Before Variable"
                        value = "before-value"
                    }
                    scatterGather {
                        docName = "SG"
                        route {
                            setPayload {
                                docName = "R1"
                                value = "r1"
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("before-value", result.variables["before"]?.value)
        }

    @Test
    fun `should merge same variable from multiple routes into list`() =
        runTest {
            val flow =
                flow("scatter-gather-variable-merge") {
                    scatterGather {
                        docName = "SG Variable Merge"
                        route {
                            setVariable("sharedVar") {
                                docName = "Route 1 Variable"
                                value = "value-from-route-1"
                            }
                            setPayload { value = "r1" }
                        }
                        route {
                            setVariable("sharedVar") {
                                docName = "Route 2 Variable"
                                value = "value-from-route-2"
                            }
                            setPayload { value = "r2" }
                        }
                        route {
                            setPayload { value = "r3" }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals(
                listOf<Any?>("value-from-route-1", "value-from-route-2"),
                assertIs<List<*>>(result.variables["sharedVar"]?.value).map { it },
            )
        }

    @Test
    fun `should enforce timeout and produce MULE TIMEOUT`() =
        runTest {
            var slowRouteCompleted = false
            val flow =
                flow("scatter-gather-timeout") {
                    scatterGather {
                        docName = "SG Timeout"
                        timeout = 100
                        route {
                            setPayload { value = "fast" }
                        }
                        route {
                            processor(CaptureConfig(docName = "Slow Route", docId = "slow-route"))
                        }
                    }
                }
            val factory =
                factory { component, context ->
                    if (component.docId == "slow-route") {
                        delay(500)
                        slowRouteCompleted = true
                    }
                    ProcessorResult.Success(context)
                }

            val result = EngineFlowHarness(this, flow, factory).execute()

            assertEquals(ErrorType.Mule.TIMEOUT, result.error?.errorType)
            assertFalse(slowRouteCompleted)
        }

    @Test
    fun `should enforce maxConcurrency and execute sequentially when set to 1`() =
        runTest {
            val executionOrder = mutableListOf<String>()
            val flow =
                flow("scatter-gather-max-concurrency") {
                    scatterGather {
                        docName = "SG Max Concurrency"
                        maxConcurrency = 1
                        route {
                            processor(CaptureConfig(docName = "Route 1", docId = "route-1"))
                        }
                        route {
                            processor(CaptureConfig(docName = "Route 2", docId = "route-2"))
                        }
                    }
                }
            val factory =
                factory { component, context ->
                    executionOrder += "${component.docId}-start"
                    delay(50)
                    executionOrder += "${component.docId}-end"
                    ProcessorResult.Success(
                        context.withEvent(
                            context.event.copy(payload = TypedValue.of(component.docId)),
                        ),
                    )
                }

            val result = EngineFlowHarness(this, flow, factory).execute()

            assertEquals(
                listOf("route-1-start", "route-1-end", "route-2-start", "route-2-end"),
                executionOrder,
            )
            assertEquals(listOf<Any?>("route-1", "route-2"), aggregatedPayloads(result))
        }

    @Test
    fun `should produce MULE COMPOSITE_ROUTING error when route fails`() =
        runTest {
            val flow =
                flow("scatter-gather-composite-error") {
                    scatterGather {
                        docName = "SG Composite Error"
                        route {
                            setPayload { value = "success-route" }
                        }
                        route {
                            raiseError {
                                type = "MULE:ANY"
                                description = "Route failed intentionally"
                            }
                        }
                    }
                    errorHandler {
                        onErrorContinue(ErrorType.Mule.COMPOSITE_ROUTING) {
                            add(
                                SetPayloadConfig(
                                    docName = "Capture Composite Error Type",
                                    docId = "capture-sg-error-type",
                                    value = "#[error.errorType.identifier]",
                                ),
                            )
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals(ErrorType.Mule.COMPOSITE_ROUTING.identifier, result.payload?.value)
            assertNull(result.error)
        }

    private fun aggregatedPayloads(event: MuseEvent): List<Any?> =
        payloads(assertIs<List<*>>(event.payload?.value))

    private fun payloads(events: List<*>): List<Any?> =
        events.map { assertIs<MuseEvent>(it).payload?.value }

    private fun factory(
        capture: suspend (CaptureConfig, ExecutionContext) -> ProcessorResult = { _, context ->
            ProcessorResult.Success(context)
        },
    ): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context -> capture(component, context) }
            }
            registerPolicyRouter(ScatterGatherConfig::class, scatterGatherPolicyDeclaration)
        }
}

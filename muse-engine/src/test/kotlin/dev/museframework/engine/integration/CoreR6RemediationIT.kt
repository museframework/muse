package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.*
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.components.processor.raiseErrorProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.scatterGatherPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreR6RemediationIT {
    @Test
    fun `scatter-gather evaluates target value against aggregated route results`() = runTest {
        val flow =
            flow("scatter-gather-target-value") {
                scatterGather {
                    target = "routeCount"
                    targetValue = "#[sizeOf(payload)]"
                    route {
                        setPayload { value = "first" }
                    }
                    route {
                        setPayload { value = "second" }
                    }
                }
            }

        val result =
            EngineFlowHarness(this, flow, factory()).execute(
                MuseEvent(payload = TypedValue.of("original")),
            )

        assertEquals("original", result.payload?.value)
        assertEquals(2, result.variables["routeCount"]?.value)
    }

    @Test
    fun `scatter-gather target value can project a route field without changing the caller event`() = runTest {
        val flow =
            flow("scatter-gather-target-field") {
                scatterGather {
                    target = "firstRoutePayload"
                    targetValue = "#[payload[0].payload.value]"
                    route { setPayload { value = "first" } }
                    route { setPayload { value = "second" } }
                }
            }
        val input =
            MuseEvent(
                payload = TypedValue.of("original"),
                attributes = TypedValue.of(mapOf("request" to "r1")),
                correlationId = "scatter-target-correlation",
            )

        val result = EngineFlowHarness(this, flow, factory()).execute(input)

        assertEquals("first", result.variables["firstRoutePayload"]?.value)
        assertEquals(input.payload, result.payload)
        assertEquals(input.attributes, result.attributes)
        assertEquals(input.correlationId, result.correlationId)
    }

    @Test
    fun `scatter-gather retains null target projections and default aggregate targets`() = runTest {
        val nullable =
            flow("scatter-gather-null-target") {
                scatterGather {
                    target = "nullable"
                    targetValue = "#[null]"
                    route { setPayload { value = "route" } }
                }
            }

        val nullResult =
            EngineFlowHarness(this, nullable, factory()).execute(MuseEvent(payload = TypedValue.of("original")))

        assertTrue(nullResult.variables.containsKey("nullable"))
        assertNull(nullResult.variables["nullable"]?.value)

        val defaultTarget =
            flow("scatter-gather-default-target") {
                scatterGather {
                    target = "routes"
                    route { setPayload { value = "first" } }
                    route { setPayload { value = "second" } }
                }
            }

        val defaultResult =
            EngineFlowHarness(this, defaultTarget, factory()).execute(MuseEvent(payload = TypedValue.of("original")))

        assertEquals("original", defaultResult.payload?.value)
        assertEquals(
            listOf("first", "second"),
            assertIs<List<*>>(defaultResult.variables["routes"]?.value)
                .map { assertIs<MuseEvent>(it).payload?.value },
        )
    }

    @Test
    fun `scatter-gather merges route variables before an explicit target collision is applied`() = runTest {
        val flow =
            flow("scatter-gather-target-variable-collision") {
                scatterGather {
                    target = "shared"
                    targetValue = "#[sizeOf(payload)]"
                    route {
                        setVariable("shared") { value = "left" }
                        setVariable("onlyOneRoute") { value = "only" }
                        setPayload { value = "left" }
                    }
                    route {
                        setVariable("shared") { value = "right" }
                        setPayload { value = "right" }
                    }
                }
            }

        val result =
            EngineFlowHarness(this, flow, factory()).execute(
                MuseEvent(
                    payload = TypedValue.of("original"),
                    variables = mapOf("shared" to TypedValue.of("before")),
                ),
            )

        assertEquals(2, result.variables["shared"]?.value)
        assertEquals("only", result.variables["onlyOneRoute"]?.value)
        assertEquals("original", result.payload?.value)
    }

    @Test
    fun `scatter-gather target expression failure is modeled while failing routes bypass target evaluation`() = runTest {
        val expressionFailure =
            flow("scatter-gather-target-expression-failure") {
                scatterGather {
                    target = "captured"
                    targetValue = "#[unknownReviewFunction()]"
                    route { setPayload { value = "route" } }
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.EXPRESSION) {
                        add(SetPayloadConfig("recover expression", "recover-expression", value = "recovered"))
                    }
                }
            }

        val expressionResult = EngineFlowHarness(this, expressionFailure, factory()).execute()

        assertEquals("recovered", expressionResult.payload?.value)
        assertNull(expressionResult.error)
        assertFalse(expressionResult.variables.containsKey("captured"))

        val routeFailure =
            flow("scatter-gather-target-bypassed-on-route-failure") {
                scatterGather {
                    target = "captured"
                    targetValue = "#[unknownReviewFunction()]"
                    route { raiseError { type = "APP:PRIMARY"; description = "route failed" } }
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.COMPOSITE_ROUTING) {
                        add(
                            SetPayloadConfig(
                                "capture composite",
                                "capture-composite",
                                value = "#[error.errorType.identifier]",
                            ),
                        )
                    }
                }
            }

        val failureResult =
            EngineFlowHarness(this, routeFailure, factory()).execute(
                MuseEvent(variables = mapOf("captured" to TypedValue.of("existing"))),
            )

        assertEquals(ErrorType.Mule.COMPOSITE_ROUTING.identifier, failureResult.payload?.value)
        assertEquals("existing", failureResult.variables["captured"]?.value)
        assertNull(failureResult.error)
    }

    private fun factory() =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerPolicyRouter(ScatterGatherConfig::class, scatterGatherPolicyDeclaration)
        }
}

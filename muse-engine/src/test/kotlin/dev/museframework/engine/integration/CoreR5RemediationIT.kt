package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.*
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.raiseErrorProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.execution.policy.scatterGatherPolicyDeclaration
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CoreR5RemediationIT {
    @Test
    fun `scatter-gather exposes composite routing failure to the enclosing handler`() = runTest {
        val flow =
            flow("scatter-gather-handler-boundary") {
                scatterGather {
                    route {
                        setPayload { value = "successful route" }
                    }
                    route {
                        raiseError { type = "APP:PRIMARY"; description = "route failure" }
                    }
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        add(
                            SetPayloadConfig(
                                docName = "capture composite error",
                                docId = "capture-composite-error",
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

    @Test
    fun `scatter-gather aggregates only unhandled route failures and retains indexed successes`() = runTest {
        var observedComposite: MuseError? = null
        val outerHandlerInvocations = AtomicInteger()
        val flow =
            flow("scatter-gather-composite-details") {
                scatterGather {
                    route {
                        raiseError { type = "APP:PRIMARY"; description = "first route failure" }
                    }
                    route {
                        `try` {
                            raiseError { type = "APP:LOCAL"; description = "locally recovered" }
                            errorHandler {
                                onErrorContinue(ErrorType("APP", "LOCAL")) {
                                    setPayload { value = "local recovery" }
                                }
                            }
                        }
                    }
                    route {
                        processor(CaptureConfig("delayed route", "delayed-route"))
                    }
                    route {
                        raiseError { type = "APP:SECONDARY"; description = "last route failure" }
                    }
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.COMPOSITE_ROUTING) {
                        add(CaptureConfig("outer composite handler", "outer-composite-handler"))
                    }
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { component, context ->
                    when (component.docId) {
                        "delayed-route" -> {
                            delay(50)
                            ProcessorResult.Success(
                                context.withEvent(context.event.copy(payload = TypedValue.of("delayed success"))),
                            )
                        }

                        "outer-composite-handler" -> {
                            outerHandlerInvocations.incrementAndGet()
                            observedComposite = context.event.error
                            ProcessorResult.Success(context)
                        }

                        else -> ProcessorResult.Success(context)
                    }
                },
            ).execute()

        val composite = assertNotNull(observedComposite)
        val errorMessage = assertIs<Map<*, *>>(composite.errorMessage)
        val failures = assertIs<Map<*, *>>(errorMessage["failures"])
        val successes = assertIs<Map<*, *>>(errorMessage["results"])
        assertEquals(ErrorType.Mule.COMPOSITE_ROUTING, composite.errorType)
        assertEquals(setOf("0", "3"), failures.keys)
        assertEquals(setOf("1", "2"), successes.keys)
        assertEquals(
            listOf("APP:PRIMARY", "APP:SECONDARY"),
            composite.childErrors.map { "${it.errorType.namespace}:${it.errorType.identifier}" },
        )
        assertEquals(
            "local recovery",
            assertIs<MuseEvent>(successes["1"]).payload?.value,
        )
        assertEquals(
            "delayed success",
            assertIs<MuseEvent>(successes["2"]).payload?.value,
        )
        assertEquals(1, outerHandlerInvocations.get())
        assertNull(result.error)
    }

    private fun factory(
        capture: suspend (CaptureConfig, ExecutionContext) -> ProcessorResult = { _, context ->
            ProcessorResult.Success(context)
        },
    ) =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context -> capture(component, context) }
            }
            registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
            registerPolicyRouter(ScatterGatherConfig::class, scatterGatherPolicyDeclaration)
        }
}

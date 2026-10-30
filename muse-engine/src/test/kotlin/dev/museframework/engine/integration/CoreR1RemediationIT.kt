package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.dsl.*
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.raiseErrorProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.flowRefPolicyDeclaration
import dev.museframework.engine.execution.policy.choicePolicyDeclaration
import dev.museframework.engine.execution.policy.forEachPolicyDeclaration
import dev.museframework.engine.execution.policy.scatterGatherPolicyDeclaration
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CoreR1RemediationIT {
    private val primary = ErrorType("APP", "PRIMARY")

    @Test
    fun `handler executes all steps while error remains readable`() = runTest {
        val flow =
            flow("handler-steps") {
                raiseError { type = "APP:PRIMARY"; description = "original error" }
                errorHandler {
                    onErrorContinue(primary) {
                        add(
                            SetVariableConfig(
                                docName = "remember",
                                docId = "remember",
                                variableName = "seen",
                                value = "#[error.description]",
                            ),
                        )
                        add(
                            SetPayloadConfig(
                                docName = "recover",
                                docId = "recover",
                                value = "#[error.description]",
                            ),
                        )
                    }
                }
            }

        val result = EngineFlowHarness(this, flow, factory()).execute()

        assertEquals("original error", result.payload?.value)
        assertEquals("original error", result.variables["seen"]?.value)
        assertNull(result.error)
    }

    @Test
    fun `continue exits failed try body and resumes after scope`() = runTest {
        val seen = mutableListOf<String>()
        val flow =
            flow("continue-boundary") {
                `try` {
                    raiseError { type = "APP:PRIMARY"; description = "failure" }
                    processor(CaptureConfig("unreachable", "inside-after-error"))
                    errorHandler {
                        onErrorContinue(primary) {
                            add(CaptureConfig("recover", "handler"))
                        }
                    }
                }
                processor(CaptureConfig("resume", "outside-after-try"))
            }

        EngineFlowHarness(
            this,
            flow,
            factory { component, context ->
                seen += component.docId
                ProcessorResult.Success(context)
            },
        ).execute()

        assertEquals(listOf("handler", "outside-after-try"), seen)
    }

    @Test
    fun `flow handler recovery stops at a try without a local handler`() = runTest {
        val seen = mutableListOf<String>()
        val flow =
            flow("flow-handler-try-boundary") {
                `try` {
                    raiseError { type = "APP:PRIMARY"; description = "failure" }
                }
                processor(CaptureConfig("unreachable after flow recovery", "after-flow-handler"))
                errorHandler {
                    onErrorContinue(primary) {
                        add(CaptureConfig("recover at flow boundary", "flow-handler"))
                    }
                }
            }

        EngineFlowHarness(
            this,
            flow,
            factory { component, context ->
                seen += component.docId
                ProcessorResult.Success(context)
            },
        ).execute()

        assertEquals(listOf("flow-handler"), seen)
    }

    @Test
    fun `secondary handler failure is not cleared as recovery`() = runTest {
        val flow =
            flow("secondary-error") {
                raiseError { type = "APP:PRIMARY"; description = "primary" }
                errorHandler {
                    onErrorContinue(primary) {
                        add(
                            RaiseErrorConfig(
                                docName = "secondary",
                                docId = "secondary",
                                type = "APP:SECONDARY",
                                description = "secondary",
                            ),
                        )
                    }
                }
            }

        val result = EngineFlowHarness(this, flow, factory()).execute()

        assertEquals("APP", result.error?.errorType?.namespace)
        assertEquals("SECONDARY", result.error?.errorType?.identifier)
    }

    @Test
    fun `propagate retains strategy event mutations for the enclosing handler`() = runTest {
        var observed: MuseEvent? = null
        val flow =
            flow("propagate-event-state") {
                `try` {
                    raiseError { type = "APP:PRIMARY"; description = "primary" }
                    errorHandler {
                        onErrorPropagate(primary) {
                            add(CaptureConfig("mutate strategy event", "mutate-strategy-event"))
                        }
                    }
                }
                errorHandler {
                    onErrorContinue(primary) {
                        add(CaptureConfig("observe propagated event", "observe-propagated-event"))
                    }
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { component, context ->
                    when (component.docId) {
                        "mutate-strategy-event" ->
                            ProcessorResult.Success(
                                context.withEvent(
                                    context.event.copy(
                                        payload = TypedValue.of("strategy payload"),
                                        attributes = TypedValue.of(mapOf("source" to "strategy")),
                                        variables = context.event.variables + ("strategy" to TypedValue.of("set")),
                                    ),
                                ),
                            )

                        "observe-propagated-event" -> {
                            observed = context.event
                            ProcessorResult.Success(context)
                        }

                        else -> ProcessorResult.Success(context)
                    }
                },
            ).execute(
                MuseEvent(
                    payload = TypedValue.of("input"),
                    attributes = TypedValue.of(mapOf("source" to "input")),
                    variables = mapOf("before" to TypedValue.of("kept")),
                ),
            )

        assertEquals("APP", observed?.error?.errorType?.namespace)
        assertEquals("PRIMARY", observed?.error?.errorType?.identifier)
        assertEquals("strategy payload", observed?.payload?.value)
        assertEquals(mapOf("source" to "strategy"), observed?.attributes?.value)
        assertEquals("kept", observed?.variables?.get("before")?.value)
        assertEquals("set", observed?.variables?.get("strategy")?.value)
        assertNull(result.error)
    }

    @Test
    fun `secondary failure reaches an enclosing ANY handler once`() = runTest {
        var invocations = 0
        var observedType: String? = null
        val flow =
            flow("bounded-secondary-failure") {
                `try` {
                    raiseError { type = "APP:PRIMARY"; description = "primary" }
                    errorHandler {
                        onErrorContinue(primary) {
                            add(
                                RaiseErrorConfig(
                                    docName = "secondary",
                                    docId = "secondary",
                                    type = "APP:SECONDARY",
                                    description = "secondary",
                                ),
                            )
                        }
                    }
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.ANY) {
                        add(CaptureConfig("capture secondary", "capture-secondary"))
                    }
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { component, context ->
                    if (component.docId == "capture-secondary") {
                        invocations += 1
                        observedType = "${context.event.error?.errorType?.namespace}:${context.event.error?.errorType?.identifier}"
                    }
                    ProcessorResult.Success(context)
                },
            ).execute()

        assertEquals(1, invocations)
        assertEquals("APP:SECONDARY", observedType)
        assertNull(result.error)
    }

    @Test
    fun `recovery crosses transparent choice and subflow without changing correlation or registry`() = runTest {
        val correlations = mutableListOf<String>()
        val registries = mutableListOf<Any>()
        val child =
            subFlow("transparent-recovery") {
                `try` {
                    raiseError { type = "APP:PRIMARY"; description = "child failure" }
                    errorHandler {
                        onErrorContinue(primary) {
                            add(CaptureConfig("recover child", "recover-child"))
                        }
                    }
                }
            }
        val flow =
            flow("transparent-recovery-parent") {
                choice {
                    `when`("#[true]") {
                        flowRef(child) {}
                    }
                }
                processor(CaptureConfig("after recovery", "after-recovery"))
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { component, context ->
                    if (component.docId == "recover-child" || component.docId == "after-recovery") {
                        correlations += context.event.correlationId
                        registries += context.streamingResources
                    }
                    ProcessorResult.Success(context)
                },
                additionalFlows = listOf(child),
            ).execute(MuseEvent(correlationId = "stable-correlation"))

        assertEquals(listOf("stable-correlation", "stable-correlation"), correlations)
        assertEquals(2, registries.size)
        assertSame(registries[0], registries[1])
        assertNull(result.error)
    }

    private fun factory(
        capture: suspend (CaptureConfig, ExecutionContext) -> ProcessorResult = { _, context ->
            ProcessorResult.Success(context)
        },
    ) =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context -> capture(component, context) }
            }
            registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
            registerPolicyRouter(ChoiceConfig::class, choicePolicyDeclaration)
            registerPolicyRouter(ForEachConfig::class, forEachPolicyDeclaration)
            registerPolicyRouter(ScatterGatherConfig::class, scatterGatherPolicyDeclaration)
            registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
        }
}

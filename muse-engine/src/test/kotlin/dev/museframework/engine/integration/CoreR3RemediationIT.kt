package dev.museframework.engine.integration

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
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.raiseErrorProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.flowRefPolicyDeclaration
import dev.museframework.engine.execution.policy.forEachPolicyDeclaration
import dev.museframework.engine.execution.policy.scatterGatherPolicyDeclaration
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CoreR3RemediationIT {
    private val primary = ErrorType("APP", "PRIMARY")

    @Test
    fun `referenced flow restores caller handler after success`() = runTest {
        val child =
            flow("child-success") {
                setPayload { value = "child-success" }
                errorHandler {
                    onErrorContinue(primary) {
                        add(SetPayloadConfig("child-handler", "child-handler", value = "child-handler"))
                    }
                }
            }
        val parent =
            flow("parent-success") {
                flowRef(child) {}
                raiseError { type = "APP:PRIMARY"; description = "parent error" }
                errorHandler {
                    onErrorContinue(primary) {
                        add(SetPayloadConfig("parent-handler", "parent-handler", value = "parent-handler"))
                    }
                }
            }

        val result = EngineFlowHarness(this, parent, factory(), additionalFlows = listOf(child)).execute()

        assertEquals("parent-handler", result.payload?.value)
    }

    @Test
    fun `propagated referenced flow error reaches caller handler`() = runTest {
        val child =
            flow("child-propagate") {
                raiseError { type = "APP:PRIMARY"; description = "child error" }
                errorHandler {
                    onErrorPropagate(primary) {}
                }
            }
        val parent =
            flow("parent-propagate") {
                flowRef(child) {}
                errorHandler {
                    onErrorContinue(primary) {
                        add(SetPayloadConfig("parent-handler", "parent-handler", value = "parent-handler"))
                    }
                }
            }

        val result = EngineFlowHarness(this, parent, factory(), additionalFlows = listOf(child)).execute()

        assertEquals("parent-handler", result.payload?.value)
        assertNull(result.error)
    }

    @Test
    fun `child recovery and later parent failure select separate handlers`() = runTest {
        var childInvocations = 0
        var parentInvocations = 0
        val child =
            flow("child-continue") {
                raiseError { type = "APP:PRIMARY"; description = "child error" }
                errorHandler {
                    onErrorContinue(primary) {
                        add(CaptureConfig("child handler", "child-handler"))
                    }
                }
            }
        val parent =
            flow("parent-after-child-continue") {
                flowRef(child) {}
                raiseError { type = "APP:PRIMARY"; description = "parent error" }
                errorHandler {
                    onErrorContinue(primary) {
                        add(CaptureConfig("parent handler", "parent-handler"))
                    }
                }
            }

        val result =
            EngineFlowHarness(
                this,
                parent,
                factory { component, context ->
                    when (component.docId) {
                        "child-handler" -> childInvocations += 1
                        "parent-handler" -> parentInvocations += 1
                    }
                    ProcessorResult.Success(context)
                },
                additionalFlows = listOf(child),
            ).execute()

        assertEquals(1, childInvocations)
        assertEquals(1, parentInvocations)
        assertNull(result.error)
    }

    @Test
    fun `three level reference without child handlers reaches the outer handler once`() = runTest {
        var parentInvocations = 0
        val leaf =
            flow("leaf-no-handler") {
                raiseError { type = "APP:PRIMARY"; description = "leaf error" }
            }
        val middle =
            flow("middle-no-handler") {
                flowRef(leaf) {}
            }
        val parent =
            flow("parent-three-level") {
                flowRef(middle) {}
                errorHandler {
                    onErrorContinue(primary) {
                        add(CaptureConfig("outer handler", "outer-handler"))
                    }
                }
            }

        val result =
            EngineFlowHarness(
                this,
                parent,
                factory { component, context ->
                    if (component.docId == "outer-handler") {
                        parentInvocations += 1
                        assertEquals("leaf error", context.event.error?.description)
                    }
                    ProcessorResult.Success(context)
                },
                additionalFlows = listOf(leaf, middle),
            ).execute()

        assertEquals(1, parentInvocations)
        assertNull(result.error)
    }

    @Test
    fun `successful referenced flow returns with caller correlation and streaming registry`() = runTest {
        var childCorrelation: String? = null
        var childRegistry: Any? = null
        var parentCorrelation: String? = null
        var parentRegistry: Any? = null
        val child =
            flow("child-metadata") {
                processor(CaptureConfig("child", "child"))
            }
        val parent =
            flow("parent-metadata") {
                flowRef(child) {}
                processor(CaptureConfig("parent", "parent"))
            }

        val result =
            EngineFlowHarness(
                this,
                parent,
                factory { component, context ->
                    when (component.docId) {
                        "child" -> {
                            childCorrelation = context.event.correlationId
                            childRegistry = context.streamingResources
                        }

                        "parent" -> {
                            parentCorrelation = context.event.correlationId
                            parentRegistry = context.streamingResources
                        }
                    }
                    ProcessorResult.Success(context)
                },
                additionalFlows = listOf(child),
            ).execute(MuseEvent(correlationId = "caller-correlation"))

        assertEquals("caller-correlation", childCorrelation)
        assertEquals("caller-correlation", parentCorrelation)
        assertSame(childRegistry, parentRegistry)
        assertEquals("caller-correlation", result.correlationId)
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
            registerPolicyRouter(ForEachConfig::class, forEachPolicyDeclaration)
            registerPolicyRouter(ScatterGatherConfig::class, scatterGatherPolicyDeclaration)
            registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
        }
}

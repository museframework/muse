package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.dsl.*
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
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
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CoreR4RemediationIT {
    @Test
    fun `failed targeted flow reference preserves failure and leaves target unset`() = runTest {
        val child =
            subFlow("target-child") {
                raiseError { type = "APP:PRIMARY"; description = "child error" }
            }
        val parent =
            flow("target-parent") {
                flowRef(child) {
                    targetConfig = TargetConfig.of("captured")
                }
            }

        val result =
            EngineFlowHarness(this, parent, factory(), additionalFlows = listOf(child)).execute(
                MuseEvent(payload = TypedValue.of("input")),
            )

        assertEquals("APP", result.error?.errorType?.namespace)
        assertEquals("PRIMARY", result.error?.errorType?.identifier)
        assertFalse(result.variables.containsKey("captured"))
    }

    @Test
    fun `invalid target expression is modeled and does not set target`() = runTest {
        val child =
            subFlow("target-expression-child") {
                setPayload { value = "child result" }
            }
        val parent =
            flow("target-expression-parent") {
                flowRef(child) {
                    targetConfig = TargetConfig.of("captured", "#[unknownReviewFunction()]")
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.EXPRESSION) {
                        add(SetPayloadConfig("recover", "recover", value = "recovered"))
                    }
                }
            }

        val result = EngineFlowHarness(this, parent, factory(), additionalFlows = listOf(child)).execute()

        assertEquals("recovered", result.payload?.value)
        assertNull(result.error)
        assertFalse(result.variables.containsKey("captured"))
    }

    @Test
    fun `failed target capture keeps the existing variable and bypasses target expression`() = runTest {
        val child =
            subFlow("target-failure-child") {
                raiseError { type = "APP:PRIMARY"; description = "child error" }
            }
        val parent =
            flow("target-failure-parent") {
                flowRef(child) {
                    targetConfig = TargetConfig.of("captured", "#[unknownReviewFunction()]")
                }
            }

        val result =
            EngineFlowHarness(this, parent, factory(), additionalFlows = listOf(child)).execute(
                MuseEvent(
                    payload = TypedValue.of("input"),
                    variables = mapOf("captured" to TypedValue.of("existing")),
                ),
            )

        assertEquals("APP", result.error?.errorType?.namespace)
        assertEquals("PRIMARY", result.error?.errorType?.identifier)
        assertEquals("existing", result.variables["captured"]?.value)
    }

    @Test
    fun `successful target projection preserves caller event state`() = runTest {
        val child =
            subFlow("target-projection-child") {
                setPayload { value = "child" }
            }
        val parent =
            flow("target-projection-parent") {
                flowRef(child) {
                    targetConfig = TargetConfig.of("captured", "#[payload ++ '-projection']")
                }
            }
        val input =
            MuseEvent(
                payload = TypedValue.of("input"),
                attributes = TypedValue.of(mapOf("request" to "r1")),
                variables = mapOf("kept" to TypedValue.of("value")),
                correlationId = "target-correlation",
            )

        val result = EngineFlowHarness(this, parent, factory(), additionalFlows = listOf(child)).execute(input)

        assertEquals("input", result.payload?.value)
        assertEquals(input.attributes, result.attributes)
        assertEquals("value", result.variables["kept"]?.value)
        assertEquals("child-projection", result.variables["captured"]?.value)
        assertEquals("target-correlation", result.correlationId)
        assertNull(result.error)
    }

    private fun factory() =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
            registerPolicyRouter(ForEachConfig::class, forEachPolicyDeclaration)
            registerPolicyRouter(ScatterGatherConfig::class, scatterGatherPolicyDeclaration)
            registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
        }
}

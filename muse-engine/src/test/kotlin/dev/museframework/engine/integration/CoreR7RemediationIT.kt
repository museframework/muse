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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreR7RemediationIT {
    @Test
    fun `invalid handler predicate returns modeled expression error`() = runTest {
        val flow =
            flow("handler-predicate") {
                raiseError { type = "APP:PRIMARY"; description = "original" }
                errorHandler {
                    onErrorContinue(
                        ErrorType("APP", "PRIMARY"),
                        configure = { whenExpression = "#[unknownReviewFunction()]" },
                        pipeline = {},
                    )
                }
            }

        val result = EngineFlowHarness(this, flow, factory()).execute()

        assertEquals("MULE", result.error?.errorType?.namespace)
        assertEquals("EXPRESSION", result.error?.errorType?.identifier)
        assertEquals("APP", result.error?.childErrors?.singleOrNull()?.errorType?.namespace)
        assertEquals("PRIMARY", result.error?.childErrors?.singleOrNull()?.errorType?.identifier)
    }

    @Test
    fun `handler predicate selection skips false strategies and chooses the first true strategy`() = runTest {
        val invocations = mutableListOf<String>()
        val flow =
            flow("handler-predicate-precedence") {
                raiseError { type = "APP:PRIMARY"; description = "original" }
                errorHandler {
                    onErrorContinue(
                        ErrorType("APP", "PRIMARY"),
                        configure = { whenExpression = "#[false]" },
                    ) {
                        add(CaptureConfig("false strategy", "false-strategy"))
                    }
                    onErrorContinue(
                        ErrorType("APP", "PRIMARY"),
                        configure = { whenExpression = "#[true]" },
                    ) {
                        add(CaptureConfig("true strategy", "true-strategy"))
                    }
                    onErrorContinue(ErrorType.Mule.ANY) {
                        add(CaptureConfig("fallback strategy", "fallback-strategy"))
                    }
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { component, context ->
                    invocations += component.docId
                    ProcessorResult.Success(context)
                },
            ).execute()

        assertEquals(listOf("true-strategy"), invocations)
        assertNull(result.error)
    }

    @Test
    fun `predicate failure reaches only an enclosing EXPRESSION handler`() = runTest {
        val outerHandlerInvocations = AtomicInteger()
        val localFallbackInvocations = AtomicInteger()
        val flow =
            flow("handler-predicate-outward") {
                `try` {
                    raiseError { type = "APP:PRIMARY"; description = "original" }
                    errorHandler {
                        onErrorContinue(
                            ErrorType("APP", "PRIMARY"),
                            configure = { whenExpression = "#[unknownReviewFunction()]" },
                        ) {
                            add(CaptureConfig("unreachable predicate strategy", "predicate-strategy"))
                        }
                        onErrorContinue(ErrorType.Mule.ANY) {
                            add(CaptureConfig("local fallback", "local-fallback"))
                        }
                    }
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.EXPRESSION) {
                        add(CaptureConfig("outer expression handler", "outer-expression-handler"))
                    }
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { component, context ->
                    when (component.docId) {
                        "outer-expression-handler" -> {
                            outerHandlerInvocations.incrementAndGet()
                            assertEquals(ErrorType.Mule.EXPRESSION, context.event.error?.errorType)
                            assertEquals("APP", context.event.error?.childErrors?.singleOrNull()?.errorType?.namespace)
                        }

                        "local-fallback", "predicate-strategy" -> localFallbackInvocations.incrementAndGet()
                    }
                    ProcessorResult.Success(context)
                },
            ).execute()

        assertEquals(1, outerHandlerInvocations.get())
        assertEquals(0, localFallbackInvocations.get())
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
            registerPolicyRouter(ForEachConfig::class, forEachPolicyDeclaration)
            registerPolicyRouter(ScatterGatherConfig::class, scatterGatherPolicyDeclaration)
            registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
        }
}

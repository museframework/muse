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
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
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

class CoreR2RemediationIT {
    private val primary = ErrorType("APP", "PRIMARY")

    @Test
    fun `foreach stops on failed item and propagates its error`() = runTest {
        val seen = mutableListOf<Any?>()
        val flow =
            flow("foreach-error") {
                forEach {
                    processor(CaptureConfig("item", "item"))
                }
                processor(CaptureConfig("after", "after"))
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { component, context ->
                    val item = context.event.payload?.value
                    seen += if (component.docId == "item") item else "after"
                    if (component.docId == "item" && item == 2) {
                        ProcessorResult.Failure(
                            MuseError(
                                description = "item failed",
                                detailedDescription = "item failed",
                                cause = IllegalStateException("item failed"),
                                errorType = primary,
                            ),
                            context,
                        )
                    } else {
                        ProcessorResult.Success(context)
                    }
                },
            ).execute(MuseEvent(payload = TypedValue.of(listOf(1, 2, 3))))

        assertEquals(listOf<Any?>(1, 2), seen)
        assertEquals("APP", result.error?.errorType?.namespace)
        assertEquals("PRIMARY", result.error?.errorType?.identifier)
    }

    @Test
    fun `foreach preserves failing item state and stops at first middle or last item`() = runTest {
        listOf(1, 2, 3).forEach { failedItem ->
            val seen = mutableListOf<Any?>()
            val flow =
                flow("foreach-failure-$failedItem") {
                    forEach {
                        processor(CaptureConfig("item", "item"))
                    }
                    processor(CaptureConfig("after", "after"))
                }

            val result =
                EngineFlowHarness(
                    this,
                    flow,
                    factory { component, context ->
                        val item = context.event.payload?.value
                        seen += if (component.docId == "item") item else "after"
                        val progressed =
                            context.withEvent(
                                context.event.copy(
                                    variables = context.event.variables + ("prefix" to TypedValue.of("through-$item")),
                                ),
                            )
                        if (component.docId == "item" && item == failedItem) {
                            ProcessorResult.Failure(
                                MuseError(
                                    description = "item $failedItem failed",
                                    detailedDescription = "item $failedItem failed",
                                    cause = IllegalStateException("item $failedItem failed"),
                                    errorType = primary,
                                ),
                                progressed,
                            )
                        } else {
                            ProcessorResult.Success(progressed)
                        }
                    },
                ).execute(MuseEvent(payload = TypedValue.of(listOf(1, 2, 3))))

            assertEquals((1..failedItem).map { it as Any? }, seen)
            assertEquals(failedItem, result.payload?.value)
            assertEquals(failedItem, result.variables["counter"]?.value)
            assertEquals("through-$failedItem", result.variables["prefix"]?.value)
            assertEquals("APP", result.error?.errorType?.namespace)
            assertEquals("PRIMARY", result.error?.errorType?.identifier)
        }
    }

    @Test
    fun `foreach stops on a partial final batch and keeps prior variable updates`() = runTest {
        val batches = mutableListOf<List<*>>()
        val flow =
            flow("foreach-batch-tail") {
                forEach {
                    batchSize = 2
                    processor(CaptureConfig("batch", "batch"))
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { _, context ->
                    val batch = context.event.payload?.value as List<*>
                    batches += batch
                    val progressed =
                        context.withEvent(
                            context.event.copy(
                                variables = context.event.variables + ("completedBatches" to TypedValue.of(batches.size)),
                            ),
                        )
                    if (batch == listOf(5)) {
                        ProcessorResult.Failure(
                            MuseError(
                                description = "tail failed",
                                detailedDescription = "tail failed",
                                cause = IllegalStateException("tail failed"),
                                errorType = primary,
                            ),
                            progressed,
                        )
                    } else {
                        ProcessorResult.Success(progressed)
                    }
                },
            ).execute(MuseEvent(payload = TypedValue.of(listOf(1, 2, 3, 4, 5))))

        assertEquals<List<List<*>>>(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), batches)
        assertEquals(listOf(5), result.payload?.value)
        assertEquals(3, result.variables["counter"]?.value)
        assertEquals(3, result.variables["completedBatches"]?.value)
        assertEquals("APP", result.error?.errorType?.namespace)
    }

    @Test
    fun `try recovery handles only its item and lets later foreach items run`() = runTest {
        val seen = mutableListOf<String>()
        val flow =
            flow("foreach-local-try") {
                forEach {
                    `try` {
                        processor(CaptureConfig("item", "item"))
                        errorHandler {
                            onErrorContinue(primary) {
                                add(CaptureConfig("recover", "recover"))
                            }
                        }
                    }
                    processor(CaptureConfig("after item", "after-item"))
                }
                processor(CaptureConfig("after loop", "after-loop"))
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { component, context ->
                    val item = context.event.payload?.value
                    when (component.docId) {
                        "item" -> {
                            seen += "item-$item"
                            if (item == 2) {
                                ProcessorResult.Failure(
                                    MuseError(
                                        description = "item failed",
                                        detailedDescription = "item failed",
                                        cause = IllegalStateException("item failed"),
                                        errorType = primary,
                                    ),
                                    context,
                                )
                            } else {
                                ProcessorResult.Success(context)
                            }
                        }

                        "recover" -> {
                            seen += "recover-$item"
                            ProcessorResult.Success(context)
                        }

                        "after-item" -> {
                            seen += "after-$item"
                            ProcessorResult.Success(context)
                        }

                        "after-loop" -> {
                            seen += "after-loop"
                            ProcessorResult.Success(context)
                        }

                        else -> ProcessorResult.Success(context)
                    }
                },
            ).execute(MuseEvent(payload = TypedValue.of(listOf(1, 2, 3))))

        assertEquals(
            listOf("item-1", "after-1", "item-2", "recover-2", "after-2", "item-3", "after-3", "after-loop"),
            seen,
        )
        assertNull(result.error)
    }

    private fun factory(
        capture: suspend (CaptureConfig, ExecutionContext) -> ProcessorResult,
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

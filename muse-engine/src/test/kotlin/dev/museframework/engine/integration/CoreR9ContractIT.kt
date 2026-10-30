package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.*
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.choicePolicyDeclaration
import dev.museframework.engine.execution.policy.forEachPolicyDeclaration
import dev.museframework.engine.execution.policy.parallelForEachPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreR9ContractIT {
    @Test
    fun `root message preserves the complete input event`() = runTest {
        var root: Any? = null
        val input =
            MuseEvent(
                payload = TypedValue.of(listOf("a", "b")),
                attributes = TypedValue.of(mapOf("request" to "r1")),
                variables = mapOf("kept" to TypedValue.of("v1")),
                correlationId = "review-correlation",
            )
        val flow =
            flow("root-message-contract") {
                forEach {
                    processor(CaptureConfig("capture", "capture"))
                }
            }

        val result =
            EngineFlowHarness(this, flow, factory { _, context ->
                root = context.event.variables["rootMessage"]?.value
                ProcessorResult.Success(context)
            }).execute(input)

        assertEquals(input, assertIs<MuseEvent>(root))
        assertEquals(input.payload, result.payload)
        assertEquals(input.attributes, result.attributes)
        assertEquals(input.correlationId, result.correlationId)
        assertNull(result.error)
    }

    @Test
    fun `choice stops evaluating predicates after the first match`() = runTest {
        val flow =
            flow("choice-short-circuit") {
                choice {
                    `when`("#[true]") {
                        setPayload { value = "first" }
                    }
                    `when`("#[unknownReviewFunction()]") {
                        setPayload { value = "unreachable" }
                    }
                    otherwise {
                        setPayload { value = "otherwise" }
                    }
                }
            }

        val result = EngineFlowHarness(this, flow, factory()).execute()

        assertEquals("first", result.payload?.value)
        assertNull(result.error)
    }

    @Test
    fun `choice predicate failure is modeled and does not execute either branch`() = runTest {
        val invoked = mutableListOf<String>()
        val flow =
            flow("choice-predicate-error-contract") {
                choice {
                    `when`("#[unknownReviewFunction()]") {
                        processor(CaptureConfig("selected branch", "selected-branch"))
                    }
                    otherwise {
                        processor(CaptureConfig("otherwise branch", "otherwise-branch"))
                    }
                }
                errorHandler {
                    onErrorContinue(ErrorType.Mule.EXPRESSION) {
                        add(CaptureConfig("expression recovery", "expression-recovery"))
                    }
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { component, context ->
                    invoked += component.docId
                    ProcessorResult.Success(context)
                },
            ).execute()

        assertEquals(listOf("expression-recovery"), invoked)
        assertNull(result.error)
    }

    @Test
    fun `choice without a match preserves the full incoming event`() = runTest {
        var selectedBranches = 0
        val input =
            MuseEvent(
                payload = TypedValue.of("original"),
                attributes = TypedValue.of(mapOf("request" to "r2")),
                variables = mapOf("kept" to TypedValue.of("value")),
                correlationId = "no-match-correlation",
            )
        val flow =
            flow("choice-no-match-contract") {
                choice {
                    `when`("#[false]") {
                        processor(CaptureConfig("unselected branch", "unselected-branch"))
                    }
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { _, context ->
                    selectedBranches += 1
                    ProcessorResult.Success(context)
                },
            ).execute(input)

        assertEquals(0, selectedBranches)
        assertEquals(input, result)
    }

    @Test
    fun `empty foreach performs no child work and preserves the full incoming event`() = runTest {
        var iterations = 0
        val input =
            MuseEvent(
                payload = TypedValue.of(emptyList<String>()),
                attributes = TypedValue.of(mapOf("request" to "r3")),
                variables = mapOf("kept" to TypedValue.of("value")),
                correlationId = "empty-foreach-correlation",
            )
        val flow =
            flow("foreach-empty-contract") {
                forEach {
                    processor(CaptureConfig("unreachable iteration", "unreachable-iteration"))
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { _, context ->
                    iterations += 1
                    ProcessorResult.Success(context)
                },
            ).execute(input)

        assertEquals(0, iterations)
        assertEquals(input, result)
    }

    @Test
    fun `foreach batches the tail and retains its configured root message and counter`() = runTest {
        val batches = mutableListOf<List<Int>>()
        val counters = mutableListOf<Int>()
        val roots = mutableListOf<MuseEvent>()
        val input =
            MuseEvent(
                payload = TypedValue.of(listOf(1, 2, 3, 4, 5)),
                attributes = TypedValue.of(mapOf("request" to "r5")),
                correlationId = "batch-tail-correlation",
            )
        val flow =
            flow("foreach-batch-tail-contract") {
                forEach {
                    batchSize = 2
                    rootMessageVariableName = "root"
                    counterVariableName = "batch"
                    processor(CaptureConfig("capture batch", "capture-batch"))
                }
            }

        val result =
            EngineFlowHarness(
                this,
                flow,
                factory { _, context ->
                    @Suppress("UNCHECKED_CAST")
                    batches += context.event.payload?.value as List<Int>
                    counters += context.event.variables["batch"]?.value as Int
                    roots += assertIs<MuseEvent>(context.event.variables["root"]?.value)
                    ProcessorResult.Success(context)
                },
            ).execute(input)

        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), batches)
        assertEquals(listOf(1, 2, 3), counters)
        assertTrue(roots.all { it == input })
        assertEquals(input.payload, result.payload)
        assertEquals(input.attributes, result.attributes)
        assertEquals(input.correlationId, result.correlationId)
        assertNull(result.error)
    }

    @Test
    fun `parallel foreach target projection preserves caller state`() = runTest {
        val input =
            MuseEvent(
                payload = TypedValue.of("original"),
                attributes = TypedValue.of(mapOf("request" to "r4")),
                variables = mapOf("kept" to TypedValue.of("value")),
                correlationId = "parallel-target-correlation",
            )
        val flow =
            flow("parallel-foreach-target-contract") {
                parallelForEach {
                    collection = "#[[1, 2, 3]]"
                    target = "count"
                    targetValue = "#[sizeOf(payload)]"
                    setPayload { value = "#[payload * 2]" }
                }
            }

        val result = EngineFlowHarness(this, flow, factory()).execute(input)

        assertEquals(3, result.variables["count"]?.value)
        assertEquals(input.payload, result.payload)
        assertEquals(input.attributes, result.attributes)
        assertEquals("value", result.variables["kept"]?.value)
        assertEquals(input.correlationId, result.correlationId)
        assertNull(result.error)
    }

    private fun factory(
        capture: suspend (CaptureConfig, ExecutionContext) -> ProcessorResult = { _, context ->
            ProcessorResult.Success(context)
        },
    ): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context -> capture(component, context) }
            }
            registerPolicyRouter(ChoiceConfig::class, choicePolicyDeclaration)
            registerPolicyRouter(ForEachConfig::class, forEachPolicyDeclaration)
            registerPolicyRouter(ParallelForEachConfig::class, parallelForEachPolicyDeclaration)
        }
}

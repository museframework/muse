package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.parallelForEach
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.parallelForEachPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParallelForEachIT {
    @Test
    fun `should aggregate results from parallel iterations into payload`() =
        runTest {
            val flow =
                flow("parallel-foreach-basic") {
                    setPayload {
                        docName = "Set List"
                        value = "#[['a', 'b', 'c']]"
                    }
                    parallelForEach {
                        docName = "Parallel ForEach"
                        setPayload {
                            docName = "Transform Item"
                            value = "#[payload ++ '-processed']"
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals(
                listOf("a-processed", "b-processed", "c-processed"),
                aggregatedPayloads(result),
            )
        }

    @Test
    fun `should use explicit collection expression`() =
        runTest {
            val flow =
                flow("parallel-foreach-collection") {
                    setVariable("items") {
                        docName = "Set Items"
                        value = "#[[1, 2, 3]]"
                    }
                    parallelForEach {
                        docName = "Parallel ForEach"
                        collection = "#[vars.items]"
                        setPayload {
                            docName = "Double"
                            value = "#[payload * 2]"
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals(listOf(2, 4, 6), aggregatedPayloads(result))
        }

    @Test
    fun `should store results in target variable when configured`() =
        runTest {
            val flow =
                flow("parallel-foreach-target") {
                    setPayload {
                        docName = "Original Payload"
                        value = "original"
                    }
                    parallelForEach {
                        docName = "Parallel ForEach"
                        collection = "#[['x', 'y']]"
                        target = "pfeResults"
                        setPayload {
                            docName = "Process"
                            value = "#[payload]"
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("original", result.payload?.value)
            val target = assertNotNull(result.variables["pfeResults"])
            val events = assertIs<List<*>>(target.value)
            assertEquals(listOf("x", "y"), events.map { assertIs<MuseEvent>(it).payload?.value })
        }

    @Test
    fun `should preserve input order when iterations complete out of order`() =
        runTest {
            val flow =
                flow("parallel-foreach-order") {
                    parallelForEach {
                        docName = "Parallel For Each"
                        collection = "#[[1, 2, 3]]"
                        processor(CaptureConfig(docName = "Delayed iteration", docId = "delayed-iteration"))
                    }
                }
            val factory =
                factory { component, context ->
                    if (component.docId == "delayed-iteration") {
                        val item = assertIs<Number>(context.event.payload?.value).toInt()
                        delay((4 - item) * 50L)
                        ProcessorResult.Success(
                            context.withEvent(
                                context.event.copy(payload = TypedValue.of("$item-processed")),
                            ),
                        )
                    } else {
                        ProcessorResult.Success(context)
                    }
                }

            val result = EngineFlowHarness(this, flow, factory).execute()

            assertEquals(listOf("1-processed", "2-processed", "3-processed"), aggregatedPayloads(result))
        }

    @Test
    fun `should start timeout after an iteration obtains a concurrency permit`() =
        runTest {
            val flow =
                flow("parallel-foreach-queued-timeout") {
                    parallelForEach {
                        docName = "Parallel For Each"
                        collection = "#[['first', 'second']]"
                        maxConcurrency = 1
                        timeout = 100
                        processor(CaptureConfig(docName = "Bounded iteration", docId = "bounded-iteration"))
                    }
                }
            val factory =
                factory { component, context ->
                    if (component.docId == "bounded-iteration") {
                        delay(75)
                    }
                    ProcessorResult.Success(context)
                }

            val result = EngineFlowHarness(this, flow, factory).execute()

            assertNull(result.error)
            assertEquals(listOf("first", "second"), aggregatedPayloads(result))
        }

    @Test
    fun `should aggregate child failures and timeout after all modeled outcomes complete`() =
        runTest {
            var successfulSiblingCompleted = false
            var timedOutChildCompleted = false
            var capturedCompositeError: MuseError? = null
            val outerHandlerInvocations = AtomicInteger()
            val flow =
                flow("parallel-foreach-composite-error") {
                    parallelForEach {
                        docName = "Parallel For Each"
                        collection = "#[['failure', 'timeout', 'success']]"
                        maxConcurrency = 2
                        timeout = 100
                        processor(CaptureConfig(docName = "Iteration", docId = "iteration"))
                    }
                    errorHandler {
                        onErrorContinue(ErrorType.Mule.COMPOSITE_ROUTING) {
                            add(CaptureConfig(docName = "Composite handler", docId = "composite-handler"))
                        }
                    }
                }
            val factory =
                factory { component, context ->
                    when (component.docId) {
                        "iteration" ->
                            when (context.event.payload?.value) {
                                "failure" ->
                                    ProcessorResult.Failure(
                                        error =
                                            MuseError(
                                                description = "Iteration failed intentionally",
                                                detailedDescription = "Modeled child failure",
                                                errorType = ErrorType.Mule.ANY,
                                                cause = RuntimeException("Modeled child failure"),
                                            ),
                                        context = context,
                                    )

                                "timeout" -> {
                                    delay(200)
                                    timedOutChildCompleted = true
                                    ProcessorResult.Success(context)
                                }

                                "success" -> {
                                    delay(50)
                                    successfulSiblingCompleted = true
                                    ProcessorResult.Success(context)
                                }

                                else -> ProcessorResult.Success(context)
                            }

                        "composite-handler" -> {
                            outerHandlerInvocations.incrementAndGet()
                            capturedCompositeError = context.event.error
                            ProcessorResult.Success(context)
                        }

                        else -> ProcessorResult.Success(context)
                    }
                }

            val result = EngineFlowHarness(this, flow, factory).execute()

            val compositeError = assertNotNull(capturedCompositeError)
            val errorMessage = assertIs<Map<*, *>>(compositeError.errorMessage)
            val failures = assertIs<Map<*, *>>(errorMessage["failures"])
            val successes = assertIs<Map<*, *>>(errorMessage["results"])
            assertEquals(ErrorType.Mule.COMPOSITE_ROUTING, compositeError.errorType)
            assertEquals(setOf("0", "1"), failures.keys)
            assertEquals(setOf("2"), successes.keys)
            assertEquals(
                listOf(ErrorType.Mule.ANY, ErrorType.Mule.TIMEOUT),
                compositeError.childErrors.map { it.errorType },
            )
            assertEquals(1, outerHandlerInvocations.get())
            assertTrue(successfulSiblingCompleted)
            assertFalse(timedOutChildCompleted)
            assertNull(result.error)
        }

    @Test
    fun `should not expose iteration variable mutations outside the scope`() =
        runTest {
            val flow =
                flow("parallel-foreach-variable-isolation") {
                    setVariable("existing") {
                        docName = "Initial variable"
                        value = "original"
                    }
                    parallelForEach {
                        docName = "Parallel For Each"
                        collection = "#[['first', 'second']]"
                        setVariable("existing") {
                            docName = "Change existing variable"
                            value = "changed"
                        }
                        setVariable("iterationOnly") {
                            docName = "Create iteration variable"
                            value = "child"
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("original", result.variables["existing"]?.value)
            assertNull(result.variables["iterationOnly"])
        }

    private fun aggregatedPayloads(event: MuseEvent): List<Any?> =
        assertIs<List<*>>(event.payload?.value)
            .map { assertIs<MuseEvent>(it).payload?.value }

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
            registerPolicyRouter(ParallelForEachConfig::class, parallelForEachPolicyDeclaration)
        }
}

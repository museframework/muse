package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.forEach
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.forEachPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.engine.properties.PropertiesLoader.globalPropertyBuilder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ForEachIT {
    @Test
    fun `property-based batch size groups records through the actual policy`() = runTest {
        val batches = mutableListOf<Any?>()
        val flow = flow("property-batches") {
            setPayload { value = "#[['a','b','c']]" }
            forEach {
                batchSizeExpression = "\${batch.size}"
                processor(CaptureConfig(docName = "capture", docId = "capture"))
            }
        }
        val factory = factory { event -> batches += event.payload?.value }.apply {
            registerConfig(GlobalProperty::class, globalPropertyBuilder)
        }
        EngineFlowHarness(this, flow, factory, configs = listOf(
            GlobalProperty(docName = "batch", name = "batch.size", value = "2"),
        )).execute()

        assertEquals<List<Any?>>(listOf(listOf("a", "b"), listOf("c")), batches)
    }

    @Test
    fun `should iterate over payload list by default`() =
        runTest {
            val iteratedItems = mutableListOf<Any?>()
            val flow =
                flow("foreach-default-collection") {
                    setPayload {
                        docName = "Set List Payload"
                        value = "#[['a', 'b', 'c']]"
                    }
                    forEach {
                        docName = "ForEach"
                        processor(CaptureConfig(docName = "Inside Capture", docId = "capture-inside"))
                    }
                }

            val result =
                EngineFlowHarness(this, flow, factory { event -> iteratedItems += event.payload?.value })
                    .execute()

            assertEquals(listOf<Any?>("a", "b", "c"), iteratedItems)
            assertEquals(listOf("a", "b", "c"), assertIs<List<*>>(result.payload?.value))
        }

    @Test
    fun `should iterate with explicit collection expression`() =
        runTest {
            val iteratedItems = mutableListOf<Any?>()
            val flow =
                flow("foreach-explicit-collection") {
                    setVariable("myList") {
                        docName = "Set List Variable"
                        value = "#[['x', 'y', 'z']]"
                    }
                    forEach {
                        docName = "ForEach Explicit"
                        collection = "#[vars.myList]"
                        processor(CaptureConfig(docName = "Inside Capture", docId = "capture-inside"))
                    }
                }

            EngineFlowHarness(this, flow, factory { event -> iteratedItems += event.payload?.value })
                .execute()

            assertEquals(listOf<Any?>("x", "y", "z"), iteratedItems)
        }

    @Test
    fun `should propagate variables between iterations`() =
        runTest {
            val flow =
                flow("foreach-variable-propagation") {
                    setPayload {
                        docName = "Set List"
                        value = "#[['one', 'two', 'three']]"
                    }
                    forEach {
                        docName = "ForEach"
                        setVariable("lastItem") {
                            docName = "Track Last Item"
                            value = "#[payload]"
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("three", result.variables["lastItem"]?.value)
        }

    @Test
    fun `stock foreach rejects a scalar collection with expression error`() =
        runTest {
            val iteratedItems = mutableListOf<Any?>()
            val flow =
                flow("foreach-single-item") {
                    setPayload {
                        docName = "Set Single"
                        value = "single-value"
                    }
                    forEach {
                        docName = "ForEach"
                        processor(CaptureConfig(docName = "Inside Capture", docId = "capture-inside"))
                    }
                }

            val result = EngineFlowHarness(this, flow, factory { event -> iteratedItems += event.payload?.value })
                .execute()

            assertEquals(emptyList(), iteratedItems)
            assertEquals(dev.museframework.dsl.api.model.error.ErrorType.Mule.EXPRESSION, result.error?.errorType)
        }

    @Test
    fun `should set counter variable when counterVariableName configured`() =
        runTest {
            val counterValues = mutableListOf<Any?>()
            val flow =
                flow("foreach-counter") {
                    setPayload {
                        docName = "Set List"
                        value = "#[['a', 'b', 'c']]"
                    }
                    forEach {
                        docName = "ForEach With Counter"
                        counterVariableName = "counter"
                        processor(CaptureConfig(docName = "Inside Capture", docId = "capture-inside"))
                    }
                }

            EngineFlowHarness(
                this,
                flow,
                factory { event -> counterValues += event.variables["counter"]?.value },
            ).execute()

            assertEquals(listOf<Any?>(1, 2, 3), counterValues)
        }

    @Test
    fun `should group items into batches when batchSize is set`() =
        runTest {
            val batchPayloads = mutableListOf<Any?>()
            val flow =
                flow("foreach-batch-size") {
                    setPayload {
                        docName = "Set List"
                        value = "#[['a', 'b', 'c', 'd', 'e']]"
                    }
                    forEach {
                        docName = "ForEach Batched"
                        batchSize = 2
                        processor(CaptureConfig(docName = "Inside Capture", docId = "capture-inside"))
                    }
                }

            EngineFlowHarness(this, flow, factory { event -> batchPayloads += event.payload?.value })
                .execute()

            assertEquals(
                listOf<Any?>(listOf("a", "b"), listOf("c", "d"), listOf("e")),
                batchPayloads,
            )
        }

    @Test
    fun `should set counter and rootMessage variables by default`() =
        runTest {
            var capturedCounter: Any? = null
            var capturedRootMessage: Any? = null
            val flow =
                flow("foreach-default-vars") {
                    setPayload {
                        docName = "Set List"
                        value = "#[['only-item']]"
                    }
                    forEach {
                        docName = "ForEach"
                        processor(CaptureConfig(docName = "Inside Capture", docId = "capture-inside"))
                    }
                }

            EngineFlowHarness(
                this,
                flow,
                factory { event ->
                    capturedCounter = event.variables["counter"]?.value
                    capturedRootMessage = event.variables["rootMessage"]?.value
                },
            ).execute()

            assertEquals(1, capturedCounter)
            assertNotNull(capturedRootMessage)
        }

    private fun factory(onIteration: (MuseEvent) -> Unit = {}): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context ->
                    onIteration(context.event)
                    ProcessorResult.Success(context)
                }
            }
            registerPolicyRouter(ForEachConfig::class, forEachPolicyDeclaration)
        }
}

package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.dsl.choice
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.forEach
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.choicePolicyDeclaration
import dev.museframework.engine.execution.policy.forEachPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NestedForEachIT {
    @Test
    fun `nested forEach should iterate all combinations`() =
        runTest {
            val capturedCombinations = mutableListOf<String>()
            val flow =
                flow("nested-foreach") {
                    setPayload {
                        docName = "Set Outer List"
                        value = "#[[1, 2]]"
                    }
                    forEach {
                        docName = "Outer ForEach"
                        setVariable("outerItem") {
                            docName = "Save Outer Item"
                            value = "#[payload]"
                        }
                        setVariable("innerList") {
                            docName = "Set Inner List"
                            value = "#[['a', 'b']]"
                        }
                        forEach {
                            docName = "Inner ForEach"
                            collection = "#[vars.innerList]"
                            processor(CaptureConfig(docName = "Inner Capture", docId = "capture-inside"))
                        }
                    }
                }

            EngineFlowHarness(
                this,
                flow,
                factory { event ->
                    val outerItem = event.variables["outerItem"]?.value
                    capturedCombinations += "$outerItem-${event.payload?.value}"
                },
            ).execute()

            assertEquals(listOf("1-a", "1-b", "2-a", "2-b"), capturedCombinations)
        }

    @Test
    fun `forEach with choice inside should route each item correctly`() =
        runTest {
            val flow =
                flow("foreach-with-choice") {
                    setPayload {
                        docName = "Set Roles"
                        value = "#[['admin', 'user', 'guest']]"
                    }
                    setVariable("result") {
                        docName = "Init Result"
                        value = ""
                    }
                    forEach {
                        docName = "ForEach Role"
                        choice {
                            docName = "Role Choice"
                            `when`("#[payload == 'admin']") {
                                setVariable("result") {
                                    docName = "Admin Result"
                                    value = "#[vars.result ++ 'ADMIN,']"
                                }
                            }
                            `when`("#[payload == 'user']") {
                                setVariable("result") {
                                    docName = "User Result"
                                    value = "#[vars.result ++ 'USER,']"
                                }
                            }
                            otherwise {
                                setVariable("result") {
                                    docName = "Other Result"
                                    value = "#[vars.result ++ 'OTHER,']"
                                }
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("ADMIN,USER,OTHER,", result.variables["result"]?.value)
        }

    @Test
    fun `forEach should restore original payload after iteration`() =
        runTest {
            val flow =
                flow("foreach-restores-payload") {
                    setPayload {
                        docName = "Set Original Payload"
                        value = "original-payload"
                    }
                    setVariable("items") {
                        docName = "Set Items"
                        value = "#[['x', 'y', 'z']]"
                    }
                    forEach {
                        docName = "ForEach Items"
                        collection = "#[vars.items]"
                        setPayload {
                            docName = "Modify Payload Inside"
                            value = "#[payload ++ '-modified']"
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("original-payload", result.payload?.value)
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
            registerPolicyRouter(ChoiceConfig::class, choicePolicyDeclaration)
        }
}

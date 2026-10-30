package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.dsl.choice
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.choicePolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ChoiceIT {
    @Test
    fun `should execute matching when route`() =
        runTest {
            val flow =
                flow("choice-when-test") {
                    setVariable("route") {
                        docName = "Set Route"
                        value = "A"
                    }
                    choice {
                        docName = "Route Choice"
                        `when`("#[vars.route == 'A']") {
                            setPayload {
                                docName = "Route A Payload"
                                value = "route-a"
                            }
                        }
                        `when`("#[vars.route == 'B']") {
                            setPayload {
                                docName = "Route B Payload"
                                value = "route-b"
                            }
                        }
                        otherwise {
                            setPayload {
                                docName = "Default Payload"
                                value = "default"
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("route-a", result.payload?.value)
        }

    @Test
    fun `should execute otherwise when no when matches`() =
        runTest {
            val flow =
                flow("choice-otherwise-test") {
                    setVariable("route") {
                        docName = "Set Route"
                        value = "C"
                    }
                    choice {
                        docName = "Route Choice"
                        `when`("#[vars.route == 'A']") {
                            setPayload {
                                docName = "Route A"
                                value = "route-a"
                            }
                        }
                        otherwise {
                            setPayload {
                                docName = "Default"
                                value = "default-route"
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("default-route", result.payload?.value)
        }

    @Test
    fun `should pass through when no when matches and no otherwise`() =
        runTest {
            val flow =
                flow("choice-passthrough-test") {
                    setPayload {
                        docName = "Original Payload"
                        value = "original"
                    }
                    choice {
                        docName = "Route Choice"
                        `when`("#[false]") {
                            setPayload {
                                docName = "Never"
                                value = "never"
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("original", result.payload?.value)
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerPolicyRouter(ChoiceConfig::class, choicePolicyDeclaration)
        }
}

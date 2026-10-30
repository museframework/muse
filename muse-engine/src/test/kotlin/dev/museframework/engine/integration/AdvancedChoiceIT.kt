package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.choice
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.raiseError
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.dsl.`try`
import dev.museframework.engine.components.processor.raiseErrorProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.choicePolicyDeclaration
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AdvancedChoiceIT {
    @Test
    fun `first matching when should win when multiple whens match`() =
        runTest {
            val flow =
                flow("choice-first-match") {
                    setPayload {
                        docName = "Set Payload"
                        value = "#[10]"
                    }
                    choice {
                        docName = "Multi Match Choice"
                        `when`("#[payload > 5]") {
                            setPayload {
                                docName = "First Match"
                                value = "first"
                            }
                        }
                        `when`("#[payload > 3]") {
                            setPayload {
                                docName = "Second Match"
                                value = "second"
                            }
                        }
                        otherwise {
                            setPayload {
                                docName = "Default"
                                value = "default"
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("first", result.payload?.value)
        }

    @Test
    fun `choice based on payload type check`() =
        runTest {
            val flow =
                flow("choice-type-check") {
                    setPayload {
                        docName = "Set Number"
                        value = "#[42]"
                    }
                    choice {
                        docName = "Type Check Choice"
                        `when`("#[payload is Number]") {
                            setPayload {
                                docName = "Is Number"
                                value = "is-number"
                            }
                        }
                        otherwise {
                            setPayload {
                                docName = "Not Number"
                                value = "not-number"
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("is-number", result.payload?.value)
        }

    @Test
    fun `choice inside try scope with error in chosen branch`() =
        runTest {
            val flow =
                flow("choice-in-try-error") {
                    setVariable("action") {
                        docName = "Set Action"
                        value = "fail"
                    }
                    `try` {
                        docName = "Try"
                        choice {
                            docName = "Action Choice"
                            `when`("#[vars.action == 'fail']") {
                                raiseError {
                                    docName = "Fail Action"
                                    type = "APP:ACTION_FAILED"
                                    description = "action failed"
                                }
                            }
                            otherwise {
                                setPayload {
                                    docName = "Success"
                                    value = "success"
                                }
                            }
                        }
                        errorHandler {
                            onErrorContinue(ErrorType.Mule.ANY) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Error Recovery",
                                        docId = "err-recovery-choice-001",
                                        value = "recovered-from-choice-error",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("recovered-from-choice-error", result.payload?.value)
        }

    @Test
    fun `nested choice should evaluate inner choice correctly`() =
        runTest {
            val flow =
                flow("nested-choice") {
                    setVariable("level") {
                        docName = "Set Level"
                        value = "A"
                    }
                    setVariable("sublevel") {
                        docName = "Set SubLevel"
                        value = "2"
                    }
                    choice {
                        docName = "Outer Choice"
                        `when`("#[vars.level == 'A']") {
                            choice {
                                docName = "Inner Choice"
                                `when`("#[vars.sublevel == '1']") {
                                    setPayload {
                                        docName = "A1"
                                        value = "path-A1"
                                    }
                                }
                                `when`("#[vars.sublevel == '2']") {
                                    setPayload {
                                        docName = "A2"
                                        value = "path-A2"
                                    }
                                }
                                otherwise {
                                    setPayload {
                                        docName = "A-default"
                                        value = "path-A-default"
                                    }
                                }
                            }
                        }
                        otherwise {
                            setPayload {
                                docName = "Not A"
                                value = "path-not-A"
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("path-A2", result.payload?.value)
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerPolicyRouter(ChoiceConfig::class, choicePolicyDeclaration)
            registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
        }
}

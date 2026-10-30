package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.raiseError
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.dsl.dsl.`try`
import dev.museframework.engine.components.processor.raiseErrorProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ErrorHandlingIT {
    @Test
    fun `raise-error should produce error on event`() =
        runTest {
            val flow =
                flow("raise-error-test") {
                    `try` {
                        docName = "Try"
                        raiseError {
                            docName = "Raise Error"
                            type = "APP:CUSTOM_ERROR"
                            description = "Something went wrong"
                        }
                        errorHandler {
                            onErrorContinue(ErrorType(namespace = "APP", identifier = "CUSTOM_ERROR")) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Error Recovery",
                                        docId = "error-recovery-001",
                                        value = "recovered",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("recovered", result.payload?.value)
            assertNull(result.error)
        }

    @Test
    fun `on-error-propagate should deliver error to outer handler`() =
        runTest {
            val flow =
                flow("propagate-error-test") {
                    `try` {
                        docName = "Outer Try"
                        `try` {
                            docName = "Inner Try"
                            raiseError {
                                docName = "Raise Error"
                                type = "APP:VALIDATION_ERROR"
                                description = "Invalid input"
                            }
                            errorHandler {
                                onErrorPropagate(ErrorType(namespace = "APP", identifier = "VALIDATION_ERROR")) {
                                    add(
                                        SetVariableConfig(
                                            docName = "Mark Propagated",
                                            docId = "mark-propagated-001",
                                            variableName = "propagated",
                                            value = "true",
                                        ),
                                    )
                                }
                            }
                        }
                        errorHandler {
                            onErrorContinue(ErrorType(namespace = "APP", identifier = "VALIDATION_ERROR")) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Outer Recovery",
                                        docId = "outer-recovery-001",
                                        value = "#[error.description]",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("Invalid input", result.payload?.value)
            assertEquals("true", result.variables["propagated"]?.value)
            assertNull(result.error)
        }

    @Test
    fun `try without matching error handler should propagate error`() =
        runTest {
            val flow =
                flow("no-matching-handler-test") {
                    `try` {
                        docName = "Outer Try"
                        `try` {
                            docName = "Inner Try"
                            raiseError {
                                docName = "Raise Error"
                                type = "APP:NOT_FOUND"
                                description = "Not found"
                            }
                            errorHandler {
                                onErrorContinue(ErrorType(namespace = "APP", identifier = "DIFFERENT_ERROR")) {
                                    add(
                                        SetPayloadConfig(
                                            docName = "Wrong Handler",
                                            docId = "wrong-handler-001",
                                            value = "wrong",
                                        ),
                                    )
                                }
                            }
                        }
                        errorHandler {
                            onErrorContinue(ErrorType.Mule.ANY) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Catch All",
                                        docId = "catch-all-001",
                                        value = "caught-by-any",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("caught-by-any", result.payload?.value)
            assertNull(result.error)
        }

    @Test
    fun `raise-error with expression description should evaluate expression`() =
        runTest {
            val flow =
                flow("raise-error-expr-test") {
                    setVariable("errorMsg") {
                        docName = "Set Error Msg"
                        value = "dynamic error"
                    }
                    `try` {
                        docName = "Try"
                        raiseError {
                            docName = "Raise"
                            type = "APP:DYNAMIC"
                            description = "#[vars.errorMsg]"
                        }
                        errorHandler {
                            onErrorContinue(ErrorType(namespace = "APP", identifier = "DYNAMIC")) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Recovery",
                                        docId = "recovery-expr-001",
                                        value = "#[error.description]",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("dynamic error", result.payload?.value)
        }

    @Test
    fun `error type hierarchy matching should work with parent types`() =
        runTest {
            val flow =
                flow("error-hierarchy-test") {
                    `try` {
                        docName = "Try"
                        raiseError {
                            docName = "Raise Composite Routing"
                            type = "MULE:COMPOSITE_ROUTING"
                            description = "Composite route failed"
                        }
                        errorHandler {
                            onErrorContinue(ErrorType.Mule.ROUTING) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Caught by Routing Parent",
                                        docId = "caught-routing-001",
                                        value = "caught-by-routing-parent",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("caught-by-routing-parent", result.payload?.value)
            assertNull(result.error)
        }

    @Test
    fun `try scope without error should pass through normally`() =
        runTest {
            val flow =
                flow("try-no-error-test") {
                    `try` {
                        docName = "Try"
                        setPayload {
                            docName = "Set Payload"
                            value = "success"
                        }
                        errorHandler {
                            onErrorContinue(ErrorType.Mule.ANY) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Should Not Execute",
                                        docId = "should-not-001",
                                        value = "error",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("success", result.payload?.value)
            assertNull(result.error)
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
        }
}

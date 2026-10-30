package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.raiseError
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

class AdvancedErrorHandlingIT {
    @Test
    fun `error description should be accessible via DW in error handler`() =
        runTest {
            val flow =
                flow("error-desc-access") {
                    `try` {
                        docName = "Try"
                        raiseError {
                            docName = "Raise"
                            type = "APP:TEST_ERROR"
                            description = "something broke badly"
                        }
                        errorHandler {
                            onErrorContinue(ErrorType(namespace = "APP", identifier = "TEST_ERROR")) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Set Error Desc",
                                        docId = "set-err-desc-001",
                                        value = "#[error.description]",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("something broke badly", result.payload?.value)
        }

    @Test
    fun `error errorType identifier should be accessible via DW in error handler`() =
        runTest {
            val flow =
                flow("error-type-access") {
                    `try` {
                        docName = "Try"
                        raiseError {
                            docName = "Raise"
                            type = "APP:CUSTOM_IDENTIFIER"
                            description = "test"
                        }
                        errorHandler {
                            onErrorContinue(ErrorType.Mule.ANY) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Set ErrorType Id",
                                        docId = "set-errtype-001",
                                        value = "#[error.errorType.identifier]",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("CUSTOM_IDENTIFIER", result.payload?.value)
        }

    @Test
    fun `MULE ANY should catch custom namespace errors`() =
        runTest {
            val flow =
                flow("catch-custom-namespace") {
                    `try` {
                        docName = "Try"
                        raiseError {
                            docName = "Raise Custom"
                            type = "MYAPP:SPECIAL_ERROR"
                            description = "custom ns error"
                        }
                        errorHandler {
                            onErrorContinue(ErrorType.Mule.ANY) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Caught by ANY",
                                        docId = "caught-any-002",
                                        value = "caught-custom-ns",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("caught-custom-ns", result.payload?.value)
        }

    @Test
    fun `on-error-continue should preserve variables set before error`() =
        runTest {
            val flow =
                flow("preserve-vars-on-error") {
                    setVariable("beforeError") {
                        docName = "Set Before"
                        value = "preserved-value"
                    }
                    `try` {
                        docName = "Try"
                        raiseError {
                            docName = "Raise"
                            type = "APP:FAIL"
                            description = "fail"
                        }
                        errorHandler {
                            onErrorContinue(ErrorType.Mule.ANY) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Recovery",
                                        docId = "recovery-preserve-001",
                                        value = "recovered",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("recovered", result.payload?.value)
            assertEquals("preserved-value", result.variables["beforeError"]?.value)
        }

    @Test
    fun `error errorType namespace should be accessible via DW`() =
        runTest {
            val flow =
                flow("error-ns-access") {
                    `try` {
                        docName = "Try"
                        raiseError {
                            docName = "Raise"
                            type = "INTEGRATION:TIMEOUT"
                            description = "timed out"
                        }
                        errorHandler {
                            onErrorContinue(ErrorType.Mule.ANY) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Set Namespace",
                                        docId = "set-ns-001",
                                        value = "#[error.errorType.namespace]",
                                    ),
                                )
                            }
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("INTEGRATION", result.payload?.value)
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
        }
}

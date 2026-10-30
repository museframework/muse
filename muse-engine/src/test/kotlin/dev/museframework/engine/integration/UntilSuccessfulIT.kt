package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.untilSuccessful
import dev.museframework.dsl.dsl.`try`
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.raiseErrorProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.execution.policy.untilSuccessfulPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UntilSuccessfulIT {
    @Test
    fun `should succeed on first attempt when no error`() =
        runTest {
            val flow =
                flow("until-successful-no-error") {
                    untilSuccessful {
                        docName = "Until Successful"
                        maxRetries = 3
                        millisBetweenRetries = 10
                        setPayload {
                            docName = "Success Payload"
                            value = "ok"
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("ok", result.payload?.value)
            assertNull(result.error)
        }

    @Test
    fun `should succeed after retries when processor eventually succeeds`() =
        runTest {
            var attempts = 0
            val flow =
                flow("until-successful-retry-success") {
                    untilSuccessful {
                        docName = "Until Successful"
                        maxRetries = 5
                        millisBetweenRetries = 10
                        processor(CaptureConfig(docName = "Flaky Processor", docId = "flaky-processor"))
                    }
                }
            val factory =
                factory { context ->
                    attempts += 1
                    if (attempts <= 2) {
                        failure(context, "Flaky failure attempt $attempts")
                    } else {
                        ProcessorResult.Success(
                            context.withEvent(
                                context.event.copy(payload = TypedValue.of("success-after-retries")),
                            ),
                        )
                    }
                }

            val result = EngineFlowHarness(this, flow, factory).execute()

            assertEquals("success-after-retries", result.payload?.value)
            assertEquals(3, attempts)
        }

    @Test
    fun `should produce RETRY_EXHAUSTED error when all retries fail`() =
        runTest {
            var attempts = 0
            val flow =
                flow("until-successful-exhausted") {
                    `try` {
                        docName = "Try"
                        untilSuccessful {
                            docName = "Until Successful"
                            maxRetries = 2
                            millisBetweenRetries = 10
                            processor(CaptureConfig(docName = "Always Fail", docId = "always-fail"))
                        }
                        errorHandler {
                            onErrorContinue(ErrorType.Mule.RETRY_EXHAUSTED) {
                                add(
                                    SetPayloadConfig(
                                        docName = "Recovery",
                                        docId = "recovery-001",
                                        value = "retry-exhausted-caught",
                                    ),
                                )
                            }
                        }
                    }
                }
            val factory =
                factory { context ->
                    attempts += 1
                    failure(context, "Always fails")
                }

            val result = EngineFlowHarness(this, flow, factory).execute()

            assertEquals("retry-exhausted-caught", result.payload?.value)
            assertEquals(3, attempts)
        }

    private fun factory(
        capture: suspend (ExecutionContext) -> ProcessorResult = { ProcessorResult.Success(it) },
    ): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(RaiseErrorConfig::class, raiseErrorProcessorBuilder)
            registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component, capture)
            }
            registerPolicyRouter(UntilSuccessfulConfig::class, untilSuccessfulPolicyDeclaration)
            registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
        }

    private fun failure(
        context: ExecutionContext,
        description: String,
    ): ProcessorResult.Failure =
        ProcessorResult.Failure(
            error =
                MuseError(
                    description = description,
                    detailedDescription = description,
                    errorType = ErrorType.Mule.CONNECTIVITY,
                    cause = RuntimeException(description),
                ),
            context = context,
        )
}

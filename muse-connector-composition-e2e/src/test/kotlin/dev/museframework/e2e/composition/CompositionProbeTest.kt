package dev.museframework.e2e.composition

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import dev.museframework.file.api.model.LocalFileAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.ServiceLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompositionProbeTest {
    @AfterEach
    fun resetProbe() {
        CompositionProbe.resetForTests()
    }

    @Test
    fun `attributes fileName expression resolves for LocalFileAttributes`() {
        val attributes =
            LocalFileAttributes(
                path = "/tmp/incoming/test.bin",
                fileName = "test.bin",
                lastModifiedTime = Instant.EPOCH,
                lastAccessTime = Instant.EPOCH,
                creationTime = Instant.EPOCH,
                size = 7L,
                regularFile = true,
                directory = false,
                symbolicLink = false,
            )
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        val result = evaluator.evaluate("#[attributes.fileName]", MuseEvent(attributes = TypedValue.of(attributes)))
        assertEquals("test.bin", result?.value)
    }

    @Test
    fun `registrar is discovered through service loading`() {
        val registrars = ServiceLoader.load(MuseComponentRegistrar::class.java).toList()
        assertTrue(registrars.any { it is CompositionProbeRegistrar })
    }

    @Test
    fun `admission processor returns unchanged success context`() =
        runBlocking {
            val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("probe".toByteArray())
            val event =
                MuseEvent(
                    payload = TypedValue.of(provider as Any),
                    attributes = TypedValue.of(localAttributes("probe.bin")),
                )
            val context = executionContext(event)
            val processor =
                CompositionProbeMuseProcessor(
                    CompositionProbeConfig(
                        phase = ProbePhase.ADMISSION,
                        docName = "admission",
                    ),
                )
            val result = processor.process(context)
            assertTrue(result is ProcessorResult.Success)
            assertSame(context, result.context)
            assertSame(provider, result.context.event.payload?.value)
            assertSame(event.attributes, result.context.event.attributes)
        }

    @Test
    fun `failure evidence uses source fileName and event error metadata`() =
        runBlocking {
            val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("failure".toByteArray())
            val attributes = localAttributes("failed.bin")
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(provider as Any),
                    attributes = TypedValue.of(attributes),
                ),
            )
            val failureEvent =
                MuseEvent(
                    attributes = TypedValue.of(attributes),
                    error =
                        MuseError(
                            description = "insert rejected",
                            detailedDescription = "check constraint",
                            errorType = ErrorType("DB", "QUERY_EXECUTION"),
                            cause = IllegalStateException("check"),
                        ),
                )
            val observation = CompositionProbe.recordFailure(failureEvent)
            assertEquals("failed.bin", observation.id)
            assertEquals("DB", observation.namespace)
            assertEquals("QUERY_EXECUTION", observation.identifier)
        }

    @Test
    fun `failure gate closes before failure evidence is published`() =
        runBlocking {
            val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("gate".toByteArray())
            val attributes = localAttributes("gate.bin")
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(provider as Any),
                    attributes = TypedValue.of(attributes),
                ),
            )
            val failureEvent =
                MuseEvent(
                    attributes = TypedValue.of(attributes),
                    error =
                        MuseError(
                            description = "failure",
                            detailedDescription = "failure",
                            errorType = ErrorType("DB", "QUERY_EXECUTION"),
                            cause = IllegalStateException("failure"),
                        ),
                )
            val observation = CompositionProbe.recordFailure(failureEvent)
            assertEquals("gate.bin", observation.id)

            val parked =
                async(Dispatchers.Default) {
                    CompositionProbe.admit(
                        MuseEvent(
                            payload = TypedValue.of(
                                RepeatableInMemoryCursorStreamProvider.fromBytes("park".toByteArray()) as Any,
                            ),
                            attributes = TypedValue.of(localAttributes("parked.bin")),
                        ),
                    )
                }
            Thread.sleep(200)
            assertFalse(parked.isCompleted)

            CompositionProbe.resumeRetries(observation.attemptId)
            parked.await()
        }

    @Test
    fun `matching resume releases an already parked admission`() =
        runBlocking {
            val attributes = localAttributes("retry.bin")
            val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("retry".toByteArray())
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(provider as Any),
                    attributes = TypedValue.of(attributes),
                ),
            )
            val observation =
                CompositionProbe.recordFailure(
                    failureEvent(attributes, ErrorType("DB", "QUERY_EXECUTION")),
                )
            val parked =
                async(Dispatchers.Default) {
                    CompositionProbe.admit(
                        MuseEvent(
                            payload = TypedValue.of(
                                RepeatableInMemoryCursorStreamProvider.fromBytes("next".toByteArray()) as Any,
                            ),
                            attributes = TypedValue.of(localAttributes("next.bin")),
                        ),
                    )
                }
            Thread.sleep(200)
            assertFalse(parked.isCompleted)
            CompositionProbe.resumeRetries(observation.attemptId)
            parked.await()
        }

    @Test
    fun `resume before parking still allows a later admission`() =
        runBlocking {
            val attributes = localAttributes("early-resume.bin")
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(
                        RepeatableInMemoryCursorStreamProvider.fromBytes("a".toByteArray()) as Any,
                    ),
                    attributes = TypedValue.of(attributes),
                ),
            )
            val observation =
                CompositionProbe.recordFailure(
                    failureEvent(attributes, ErrorType("DB", "QUERY_EXECUTION")),
                )
            CompositionProbe.resumeRetries(observation.attemptId)
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(
                        RepeatableInMemoryCursorStreamProvider.fromBytes("b".toByteArray()) as Any,
                    ),
                    attributes = TypedValue.of(localAttributes("after-resume.bin")),
                ),
            )
        }

    @Test
    fun `stale resume command does not reopen processing`() =
        runBlocking {
            val attributes = localAttributes("stale.bin")
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(
                        RepeatableInMemoryCursorStreamProvider.fromBytes("s".toByteArray()) as Any,
                    ),
                    attributes = TypedValue.of(attributes),
                ),
            )
            val observation =
                CompositionProbe.recordFailure(
                    failureEvent(attributes, ErrorType("DB", "QUERY_EXECUTION")),
                )
            CompositionProbe.resumeRetries(observation.attemptId + 99)
            val parked =
                async(Dispatchers.Default) {
                    CompositionProbe.admit(
                        MuseEvent(
                            payload = TypedValue.of(
                                RepeatableInMemoryCursorStreamProvider.fromBytes("p".toByteArray()) as Any,
                            ),
                            attributes = TypedValue.of(localAttributes("parked-stale.bin")),
                        ),
                    )
                }
            Thread.sleep(200)
            assertFalse(parked.isCompleted)
            parked.cancel()
        }

    @Test
    fun `shutdown cancels parked admissions without reopening the gate`() {
        runBlocking {
            val attributes = localAttributes("shutdown.bin")
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(
                        RepeatableInMemoryCursorStreamProvider.fromBytes("x".toByteArray()) as Any,
                    ),
                    attributes = TypedValue.of(attributes),
                ),
            )
            CompositionProbe.recordFailure(
                failureEvent(attributes, ErrorType("DB", "QUERY_EXECUTION")),
            )
            val parked =
                async(Dispatchers.Default) {
                    CompositionProbe.admit(
                        MuseEvent(
                            payload = TypedValue.of(
                                RepeatableInMemoryCursorStreamProvider.fromBytes("y".toByteArray()) as Any,
                            ),
                            attributes = TypedValue.of(localAttributes("cancelled.bin")),
                        ),
                    )
                }
            Thread.sleep(200)
            CompositionProbe.signalShutdown()
            assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
                parked.await()
            }
        }
    }

    @Test
    fun `release observer observes the provider captured at admission`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("release".toByteArray())
        val attributes = localAttributes("release.bin")
        runBlocking {
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(provider as Any),
                    attributes = TypedValue.of(attributes),
                ),
            )
        }
        val observation =
            CompositionProbe.recordFailure(
                MuseEvent(
                    attributes = TypedValue.of(attributes),
                    payload = null,
                    error =
                        MuseError(
                            description = "failure",
                            detailedDescription = "failure",
                            errorType = ErrorType("DB", "QUERY_EXECUTION"),
                            cause = IllegalStateException("failure"),
                        ),
                ),
            )
        assertFalse(provider.isClosed)
        provider.releaseResources()
        val release =
            CompositionProbe.awaitRelease(
                observation.attemptId,
                Duration.ofSeconds(5),
            )
        assertTrue(release.providerClosed)
        assertFalse(release.managed)
    }

    @Test
    fun `matching resume completes admission paused before waiter enrollment`() {
        val pausedAtEnrollment = CountDownLatch(1)
        val continueEnrollment = CountDownLatch(1)
        CompositionProbe.enrollmentPausedForTests = pausedAtEnrollment
        CompositionProbe.enrollmentContinueForTests = continueEnrollment

        runBlocking {
            val attributes = localAttributes("race-resume.bin")
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(
                        RepeatableInMemoryCursorStreamProvider.fromBytes("first".toByteArray()) as Any,
                    ),
                    attributes = TypedValue.of(attributes),
                ),
            )
            val observation =
                CompositionProbe.recordFailure(
                    failureEvent(attributes, ErrorType("DB", "QUERY_EXECUTION")),
                )
            val parked =
                async(Dispatchers.Default) {
                    CompositionProbe.admit(
                        MuseEvent(
                            payload = TypedValue.of(
                                RepeatableInMemoryCursorStreamProvider.fromBytes("parked".toByteArray()) as Any,
                            ),
                            attributes = TypedValue.of(localAttributes("parked-resume.bin")),
                        ),
                    )
                }
            check(pausedAtEnrollment.await(5, TimeUnit.SECONDS))
            CompositionProbe.resumeRetries(observation.attemptId)
            continueEnrollment.countDown()
            parked.await()
        }
    }

    @Test
    fun `shutdown cancels admission paused before waiter enrollment`() {
        val pausedAtEnrollment = CountDownLatch(1)
        val continueEnrollment = CountDownLatch(1)
        CompositionProbe.enrollmentPausedForTests = pausedAtEnrollment
        CompositionProbe.enrollmentContinueForTests = continueEnrollment

        runBlocking {
            val attributes = localAttributes("race-shutdown.bin")
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(
                        RepeatableInMemoryCursorStreamProvider.fromBytes("first".toByteArray()) as Any,
                    ),
                    attributes = TypedValue.of(attributes),
                ),
            )
            CompositionProbe.recordFailure(
                failureEvent(attributes, ErrorType("DB", "QUERY_EXECUTION")),
            )
            val parked =
                async(Dispatchers.Default) {
                    CompositionProbe.admit(
                        MuseEvent(
                            payload = TypedValue.of(
                                RepeatableInMemoryCursorStreamProvider.fromBytes("parked".toByteArray()) as Any,
                            ),
                            attributes = TypedValue.of(localAttributes("parked-shutdown.bin")),
                        ),
                    )
                }
            check(pausedAtEnrollment.await(5, TimeUnit.SECONDS))
            CompositionProbe.signalShutdown()
            continueEnrollment.countDown()
            assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
                parked.await()
            }
        }
    }

    @Test
    fun `lone completed success discards its provider association`() {
        val successProvider = RepeatableInMemoryCursorStreamProvider.fromBytes("success".toByteArray())
        runBlocking {
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(successProvider as Any),
                    attributes = TypedValue.of(localAttributes("success.bin")),
                ),
            )
            assertEquals(1, CompositionProbe.retainedProviderAssociationCountForTests())
            successProvider.releaseResources()
            CompositionProbe.completeSuccessfulAttempt()
            assertEquals(0, CompositionProbe.retainedProviderAssociationCountForTests())
        }
    }

    @Test
    fun `shutdown during provider publication does not retain a late provider`() {
        val pausedAtPublication = CountDownLatch(1)
        val continuePublication = CountDownLatch(1)
        CompositionProbe.providerPublicationPausedForTests = pausedAtPublication
        CompositionProbe.providerPublicationContinueForTests = continuePublication

        runBlocking {
            val parked =
                async(Dispatchers.Default) {
                    CompositionProbe.admit(
                        MuseEvent(
                            payload = TypedValue.of(
                                RepeatableInMemoryCursorStreamProvider.fromBytes("late".toByteArray()) as Any,
                            ),
                            attributes = TypedValue.of(localAttributes("late.bin")),
                        ),
                    )
                }
            check(pausedAtPublication.await(5, TimeUnit.SECONDS))
            CompositionProbe.signalShutdown()
            continuePublication.countDown()
            assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
                parked.await()
            }
            assertEquals(0, CompositionProbe.retainedProviderAssociationCountForTests())
            assertEquals(null, CompositionProbe.activeAttemptIdForTests())
        }
    }

    @Test
    fun `failed attempt keeps provider association until observer completion`() {
        val failureProvider = RepeatableInMemoryCursorStreamProvider.fromBytes("failure".toByteArray())
        runBlocking {
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(failureProvider as Any),
                    attributes = TypedValue.of(localAttributes("failure.bin")),
                ),
            )
            val observation =
                CompositionProbe.recordFailure(
                    failureEvent(localAttributes("failure.bin"), ErrorType("DB", "QUERY_EXECUTION")),
                )
            assertEquals(1, CompositionProbe.retainedProviderAssociationCountForTests())
            failureProvider.releaseResources()
            CompositionProbe.awaitRelease(observation.attemptId, Duration.ofSeconds(5))
            assertEquals(0, CompositionProbe.retainedProviderAssociationCountForTests())
        }
    }

    @Test
    fun `reset replaces a shut down observer executor`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("reset".toByteArray())
        val attributes = localAttributes("reset.bin")
        runBlocking {
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(provider as Any),
                    attributes = TypedValue.of(attributes),
                ),
            )
        }
        CompositionProbe.signalShutdown()
        CompositionProbe.resetForTests()
        runBlocking {
            CompositionProbe.admit(
                MuseEvent(
                    payload = TypedValue.of(provider as Any),
                    attributes = TypedValue.of(attributes),
                ),
            )
        }
        val observation =
            CompositionProbe.recordFailure(
                failureEvent(attributes, ErrorType("DB", "QUERY_EXECUTION")),
            )
        provider.releaseResources()
        val release =
            CompositionProbe.awaitRelease(
                observation.attemptId,
                Duration.ofSeconds(5),
            )
        assertTrue(release.providerClosed)
    }

    private fun localAttributes(fileName: String): LocalFileAttributes =
        LocalFileAttributes(
            path = "/tmp/incoming/$fileName",
            fileName = fileName,
            lastModifiedTime = Instant.EPOCH,
            lastAccessTime = Instant.EPOCH,
            creationTime = Instant.EPOCH,
            size = 1L,
            regularFile = true,
            directory = false,
            symbolicLink = false,
        )

    private fun failureEvent(
        attributes: LocalFileAttributes,
        errorType: ErrorType,
    ): MuseEvent =
        MuseEvent(
            attributes = TypedValue.of(attributes),
            error =
                MuseError(
                    description = "failure",
                    detailedDescription = "failure",
                    errorType = errorType,
                    cause = IllegalStateException("failure"),
                ),
        )

    private fun executionContext(event: MuseEvent): ExecutionContext {
        val evaluator =
            object : MuseExpressionEvaluator {
                override fun evaluate(
                    expression: String,
                    event: MuseEvent,
                ): TypedValue<Any>? = null
            }
        return ExecutionContext(
            event = event,
            errorHandler = null,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            expressionEvaluator = evaluator,
        )
    }
}

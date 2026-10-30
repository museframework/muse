package dev.museframework.engine.execution

import dev.museframework.dsl.api.config.AsyncConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.policy.BackgroundAdmission
import dev.museframework.engine.api.execution.policy.BackgroundExecutionConfig
import dev.museframework.engine.api.execution.policy.BackgroundHandle
import dev.museframework.engine.api.execution.policy.BackgroundRejection
import dev.museframework.engine.api.execution.policy.BackgroundTask
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.streaming.BackgroundStreamPreparation
import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingEventResources
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.app.MuseApplication
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.execution.policy.AsyncPolicy
import dev.museframework.engine.integration.CaptureConfig
import dev.museframework.engine.integration.ProbePolicyConfig
import dev.museframework.engine.integration.PolicyTrackingProvider
import dev.museframework.engine.integration.BackgroundLogCapture
import dev.museframework.engine.integration.policyFlow
import dev.museframework.engine.integration.policyFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ch.qos.logback.classic.spi.ILoggingEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundTaskManagerTest {

    @Test
    fun `configured capacity counts queued and running accepted work`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val config = asyncPolicyConfig(maxConcurrency = 1)
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { context ->
                entered.complete(Unit)
                release.await()
                ProcessorResult.Success(context)
            }),
            background = BackgroundExecutionConfig(maxOutstandingTasks = 2),
        ) { _, processor ->
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
            runCurrent()
            assertTrue(entered.isCompleted)

            // Queued behind the shared running limit, but still occupying a capacity slot.
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
            runCurrent()

            val overCapacity = assertIs<ProcessorResult.Failure>(processor.process(backgroundContext(this, config)))
            assertEquals(ErrorType.Mule.ASYNC_REJECTED, overCapacity.error.errorType)
            assertEquals(BackgroundRejection.CAPACITY.name, overCapacity.error.detailedDescription)

            release.complete(Unit)
            runCurrent()
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
        }
    }

    @Test
    fun `cancellation before publication leaves no task and no reservation`() = runTest {
        val preparing = CompletableDeferred<Unit>()
        val provider = PreparingProvider { preparing.await() }
        val admissions = mutableListOf<BackgroundAdmission>()
        val config = recordingPolicyConfig { admissions += it }
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { ProcessorResult.Success(it) }),
            background = BackgroundExecutionConfig(maxOutstandingTasks = 1),
        ) { _, processor ->
            val context = submittingContext(this, config, MuseEvent(payload = TypedValue.of(provider as Any)))
            val submitter = launch { processor.process(context) }
            runCurrent()
            assertEquals(1, provider.preparations.get())

            submitter.cancel()
            submitter.join()

            assertTrue(submitter.isCancelled)
            assertTrue(admissions.isEmpty(), "A cancelled admission must not publish a task")
            assertFalse(provider.isClosed, "The submitting request keeps its own claim")
            context.streamingResources.releaseAll()
            assertTrue(provider.isClosed)
            assertEquals(1, provider.releases.get(), "No provisional branch claim survived the rollback")

            // The reservation is back: capacity 1 accepts a fresh submission.
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
        }
    }

    @Test
    fun `input that cannot be retained is rejected before acceptance`() = runTest {
        val provider = PreparingProvider { throw IllegalStateException("Input was already claimed") }
        val config = asyncPolicyConfig()
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { ProcessorResult.Success(it) }),
            background = BackgroundExecutionConfig(maxOutstandingTasks = 1),
        ) { _, processor ->
            val context = submittingContext(this, config, MuseEvent(payload = TypedValue.of(provider as Any)))
            val rejected = assertIs<ProcessorResult.Failure>(processor.process(context))
            assertEquals(ErrorType.Mule.ASYNC_INPUT_NOT_RETAINABLE, rejected.error.errorType)
            assertEquals(BackgroundRejection.INPUT_NOT_RETAINABLE.name, rejected.error.detailedDescription)

            context.streamingResources.releaseAll()
            assertTrue(provider.isClosed)
            assertEquals(1, provider.releases.get(), "Only the request's own claim is released")

            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
        }
    }

    @Test
    fun `a streaming preparation failure is reported as non-retainable input`() = runTest {
        val provider = PreparingProvider { throw StreamingException("Input was detached") }
        val config = asyncPolicyConfig()
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { ProcessorResult.Success(it) }),
            background = BackgroundExecutionConfig(maxOutstandingTasks = 1),
        ) { _, processor ->
            val context = submittingContext(this, config, MuseEvent(payload = TypedValue.of(provider as Any)))
            val rejected = assertIs<ProcessorResult.Failure>(processor.process(context))
            assertEquals(ErrorType.Mule.ASYNC_INPUT_NOT_RETAINABLE, rejected.error.errorType)

            context.streamingResources.releaseAll()
            assertTrue(provider.isClosed)
            assertEquals(1, provider.releases.get())
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
        }
    }

    @Test
    fun `an unexpected preparation defect is rethrown after the reservation is restored`() = runTest {
        val provider = PreparingProvider { throw PreparationDefect("preparation defect") }
        val config = asyncPolicyConfig()
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { ProcessorResult.Success(it) }),
            background = BackgroundExecutionConfig(maxOutstandingTasks = 1),
        ) { _, processor ->
            val context = submittingContext(this, config, MuseEvent(payload = TypedValue.of(provider as Any)))
            val defect = assertFailsWith<PreparationDefect> { processor.process(context) }
            assertEquals("preparation defect", defect.message)

            context.streamingResources.releaseAll()
            assertTrue(provider.isClosed)
            assertEquals(1, provider.releases.get(), "The provisional branch claim was rolled back")
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
        }
    }

    @Test
    fun `a provider the submitting request never owned is refused without publishing`() = runTest {
        val provider = PreparingProvider { throw AssertionError("An unowned provider must never be claimed") }
        val admissions = mutableListOf<BackgroundAdmission>()
        val config = recordingPolicyConfig { admissions += it }
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { ProcessorResult.Success(it) }),
            background = BackgroundExecutionConfig(maxOutstandingTasks = 1),
        ) { _, processor ->
            // The event carries a provider the request's own registry never registered.
            val event = MuseEvent(payload = TypedValue.of(provider as Any))
            val defect = assertFailsWith<IllegalArgumentException> { processor.process(backgroundContext(this, config, event)) }
            assertTrue(defect.message!!.contains("not owned by this registry"), defect.message!!)

            assertTrue(admissions.isEmpty(), "An unowned provider must not be invented into a task")
            assertEquals(0, provider.preparations.get())
            assertFalse(provider.isClosed)

            // The reservation is back: capacity 1 accepts a valid submission.
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
        }
    }

    @Test
    fun `a closing provider is refused instead of being claimed for the branch`() = runTest {
        val provider = PreparingProvider { }
        val config = asyncPolicyConfig()
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { ProcessorResult.Success(it) }),
            background = BackgroundExecutionConfig(maxOutstandingTasks = 1),
        ) { _, processor ->
            val context = submittingContext(this, config, MuseEvent(payload = TypedValue.of(provider as Any)))
            // The identity is still owned by the request, but it is already closing: RC1 forbids
            // resurrecting it for a branch.
            provider.close()

            val rejected = assertIs<ProcessorResult.Failure>(processor.process(context))
            assertEquals(ErrorType.Mule.ASYNC_INPUT_NOT_RETAINABLE, rejected.error.errorType)

            context.streamingResources.releaseAll()
            assertEquals(1, provider.releases.get(), "No branch claim was invented for the closing identity")
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
        }
    }

    @Test
    fun `a failing event traversal restores the reserved capacity`() = runTest {
        val config = asyncPolicyConfig()
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { ProcessorResult.Success(it) }),
            background = BackgroundExecutionConfig(maxOutstandingTasks = 1),
        ) { _, processor ->
            val event = MuseEvent(payload = TypedValue.of(ExplodingList()))
            assertFailsWith<PreparationDefect> { processor.process(backgroundContext(this, config, event)) }

            // The reservation came back: capacity 1 accepts a valid submission.
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
        }
    }

    @Test
    fun `stopping during a blocked preparation publishes no task and restores its claims`() = runTest {
        val preparing = CompletableDeferred<Unit>()
        val provider = PreparingProvider { preparing.await() }
        val config = asyncPolicyConfig()
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { ProcessorResult.Success(it) }),
            background = BackgroundExecutionConfig(shutdownGraceMillis = 60_000),
        ) { application, processor ->
            val context = submittingContext(this, config, MuseEvent(payload = TypedValue.of(provider as Any)))
            val submitter = async { processor.process(context) }
            runCurrent()
            assertEquals(1, provider.preparations.get())

            application.backgroundTasks.closeAdmission()
            preparing.complete(Unit)
            runCurrent()

            val rejected = assertIs<ProcessorResult.Failure>(submitter.await())
            assertEquals(ErrorType.Mule.ASYNC_REJECTED, rejected.error.errorType)
            assertEquals(BackgroundRejection.STOPPING.name, rejected.error.detailedDescription)

            context.streamingResources.releaseAll()
            assertTrue(provider.isClosed)
            assertEquals(1, provider.releases.get(), "The provisional branch claim was rolled back")

            // Nothing reached the ledger: stop completes without draining the configured grace.
            val shutdown = launch { application.stop() }
            runCurrent()
            assertTrue(shutdown.isCompleted, "A rolled-back admission leaves no accepted task to drain")
        }
    }

    @Test
    fun `request cancellation after publication leaves accepted work running`() = runTest {
        val branchEntered = CompletableDeferred<Unit>()
        val branchRelease = CompletableDeferred<Unit>()
        val branchFinished = CompletableDeferred<Boolean>()
        val requestHold = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<BackgroundTask>()
        val config =
            ProbePolicyConfig { definition, _ ->
                val body = definition.inline(listOf(CaptureConfig("branch", "branch")))
                val background = definition.background()
                PolicyFactory {
                    ExecutionPolicy { invocation ->
                        when (val admission = invocation.submit(background, body)) {
                            is BackgroundAdmission.Accepted -> {
                                accepted.complete(admission.task)
                                // The request keeps working after the async router.
                                requestHold.await()
                                PolicyOutcome.Success(invocation.input)
                            }
                            is BackgroundAdmission.Rejected -> error("Admission was rejected: ${admission.reason}")
                        }
                    }
                }
            }
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { context ->
                branchEntered.complete(Unit)
                var cancelled = false
                try {
                    branchRelease.await()
                } catch (cancellation: CancellationException) {
                    cancelled = true
                    throw cancellation
                } finally {
                    branchFinished.complete(cancelled)
                }
                ProcessorResult.Success(context)
            }),
        ) { _, processor ->
            val submitter = launch { processor.process(backgroundContext(this, config)) }
            runCurrent()
            assertTrue(branchEntered.isCompleted)

            submitter.cancel()
            submitter.join()

            assertTrue(submitter.isCancelled, "The requesting job is cancelled")
            assertFalse(branchFinished.isCompleted, "A cancelled request must not cancel accepted work")

            branchRelease.complete(Unit)
            runCurrent()
            assertEquals(false, branchFinished.await(), "The accepted branch finished normally")
            accepted.await().join()
        }
    }

    @Test
    fun `stopping maps to the caller-owned async rejection`() = runTest {
        val config = asyncPolicyConfig()
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { ProcessorResult.Success(it) }),
        ) { application, processor ->
            application.backgroundTasks.closeAdmission()
            val rejected = assertIs<ProcessorResult.Failure>(processor.process(backgroundContext(this, config)))
            assertEquals(ErrorType.Mule.ASYNC_REJECTED, rejected.error.errorType)
            assertEquals(BackgroundRejection.STOPPING.name, rejected.error.detailedDescription)
        }
    }

    @Test
    fun `a background deadline covers queued permit wait`() = runTest {
        val deadlineCorrelationId = "corr-deadline"
        val firstEntered = CompletableDeferred<Unit>()
        val thirdEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<BackgroundTask>()
        val cancelled = AtomicBoolean(false)
        val capture = BackgroundLogCapture()
        val config =
            ProbePolicyConfig { definition, _ ->
                val body = definition.inline(listOf(CaptureConfig("branch", "branch")))
                val plain = definition.background(maxConcurrency = 1)
                val deadline = definition.background(maxConcurrency = 1, timeoutMillis = 50)
                PolicyFactory {
                    ExecutionPolicy { invocation ->
                        val submitting = invocation.input.payload?.value == "deadline"
                        when (val admission = invocation.submit(if (submitting) deadline else plain, body)) {
                            is BackgroundAdmission.Accepted -> {
                                if (submitting) queued.complete(admission.task)
                                PolicyOutcome.Success(invocation.input)
                            }
                            is BackgroundAdmission.Rejected -> error("Admission was rejected: ${admission.reason}")
                        }
                    }
                }
            }
        try {
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { context ->
                when (context.event.payload?.value) {
                    "deadline" -> error("The deadline must expire before the body runs")
                    "third" -> thirdEntered.complete(Unit)
                    else -> {
                        firstEntered.complete(Unit)
                        try {
                            firstRelease.await()
                        } catch (cancellation: CancellationException) {
                            cancelled.set(true)
                            throw cancellation
                        }
                    }
                }
                ProcessorResult.Success(context)
            }),
        ) { _, processor ->
            processor.process(backgroundContext(this, config, MuseEvent(payload = TypedValue.of("first"))))
            runCurrent()
            assertTrue(firstEntered.isCompleted)

            processor.process(
                backgroundContext(this, config, MuseEvent(payload = TypedValue.of("deadline"), correlationId = deadlineCorrelationId)),
            )
            runCurrent()

            advanceTimeBy(51)
            runCurrent()
            queued.await().join()
            assertFalse(cancelled.get(), "Only the queued task's deadline expired")

            // The first branch still owns the shared limit, so a third submission now queues.
            processor.process(backgroundContext(this, config, MuseEvent(payload = TypedValue.of("third"))))
            runCurrent()
            assertFalse(thirdEntered.isCompleted)

            firstRelease.complete(Unit)
            runCurrent()
            assertTrue(thirdEntered.isCompleted)
        }
        } finally {
            capture.close()
        }
        assertTerminalDiagnostic(capture.events, deadlineCorrelationId, "MULE:TIMEOUT")
    }

    @Test
    fun `the running limit is held until structured descendants finish`() = runTest {
        val descendantEntered = CompletableDeferred<Unit>()
        val descendantRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val config = asyncPolicyConfig(maxConcurrency = 1)
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { context ->
                if (context.event.payload?.value == "second") {
                    secondEntered.complete(Unit)
                } else {
                    context.coroutineScope.launch {
                        descendantEntered.complete(Unit)
                        descendantRelease.await()
                    }
                }
                ProcessorResult.Success(context)
            }),
        ) { _, processor ->
            processor.process(backgroundContext(this, config, MuseEvent(payload = TypedValue.of("first"))))
            runCurrent()
            assertTrue(descendantEntered.isCompleted)

            // The body returned, but its structured descendant still holds the branch's limit.
            processor.process(backgroundContext(this, config, MuseEvent(payload = TypedValue.of("second"))))
            runCurrent()
            assertFalse(secondEntered.isCompleted)

            descendantRelease.complete(Unit)
            runCurrent()
            assertTrue(secondEntered.isCompleted)
        }
    }

    @Test
    fun `an unexpected branch exception is reported once and siblings survive`() = runTest {
        val correlationId = "corr-defect"
        val siblingFinished = CompletableDeferred<Unit>()
        val tasks = mutableListOf<BackgroundTask>()
        val config = recordingPolicyConfig { tasks += assertIs<BackgroundAdmission.Accepted>(it).task }
        val capture = BackgroundLogCapture()
        try {
            withBackgroundPolicy(
                this,
                config = config,
                actions = mapOf("branch" to { context ->
                    if (context.event.payload?.value == "defect") {
                        throw IllegalStateException("branch defect")
                    }
                    siblingFinished.complete(Unit)
                    ProcessorResult.Success(context)
                }),
                background = BackgroundExecutionConfig(maxOutstandingTasks = 2),
            ) { _, processor ->
                processor.process(backgroundContext(this, config, MuseEvent(payload = TypedValue.of("defect"), correlationId = correlationId)))
                processor.process(backgroundContext(this, config, MuseEvent(payload = TypedValue.of("healthy"))))
                runCurrent()
                tasks.forEach { it.join() }

                assertTrue(siblingFinished.isCompleted, "A defective branch must not cancel its siblings")
                assertIs<ProcessorResult.Success>(
                    processor.process(backgroundContext(this, config, MuseEvent(payload = TypedValue.of("healthy")))),
                )
            }
        } finally {
            capture.close()
        }
        assertTerminalDiagnostic(capture.events, correlationId, "MULE:UNKNOWN")
    }

    @Test
    fun `a terminal modeled failure is logged exactly once`() = runTest {
        val correlationId = "corr-terminal"
        val tasks = mutableListOf<BackgroundTask>()
        val config = recordingPolicyConfig { tasks += assertIs<BackgroundAdmission.Accepted>(it).task }
        val capture = BackgroundLogCapture()
        try {
            withBackgroundPolicy(
                this,
                config = config,
                actions = mapOf("branch" to { ProcessorResult.Failure(policyError("BOOM"), it) }),
            ) { _, processor ->
                processor.process(backgroundContext(this, config, MuseEvent(correlationId = correlationId)))
                runCurrent()
                tasks.single().join()
            }
        } finally {
            capture.close()
        }
        assertTerminalDiagnostic(capture.events, correlationId, "APP:BOOM")
    }

    @Test
    fun `explicit cancel joins finalizers and releases claims once`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val observedCancellation = CompletableDeferred<Boolean>()
        val provider = PolicyTrackingProvider()
        val correlationId = "corr-cancelled"
        val tasks = mutableListOf<BackgroundTask>()
        val config = recordingPolicyConfig { tasks += assertIs<BackgroundAdmission.Accepted>(it).task }
        val capture = BackgroundLogCapture()
        try {
            withBackgroundPolicy(
                this,
                config = config,
                actions = mapOf("branch" to { context ->
                    context.streamingResources.register(provider)
                    entered.complete(Unit)
                    var cancelled = false
                    try {
                        awaitCancellation()
                    } catch (cancellation: CancellationException) {
                        cancelled = true
                        throw cancellation
                    } finally {
                        observedCancellation.complete(cancelled)
                    }
                }),
            ) { _, processor ->
                processor.process(backgroundContext(this, config, MuseEvent(correlationId = correlationId)))
                runCurrent()
                assertTrue(entered.isCompleted)
                assertFalse(provider.isClosed)

                val task = tasks.single()
                task.cancel()
                task.cancel()
                task.join()

                assertTrue(observedCancellation.await(), "Cancel must reach the branch; join must await it")
                assertTrue(provider.isClosed)
                assertEquals(1, provider.releases)
            }
        } finally {
            capture.close()
        }
        assertTrue(
            capture.events.none { it.formattedMessage.contains(correlationId) },
            "Cancellation is not a modeled branch outcome",
        )
    }

    @Test
    fun `shutdown lets accepted work finish inside the grace period`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val tasks = mutableListOf<BackgroundTask>()
        val config = recordingPolicyConfig { tasks += assertIs<BackgroundAdmission.Accepted>(it).task }
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { context ->
                entered.complete(Unit)
                try {
                    release.await()
                } catch (cancellation: CancellationException) {
                    cancelled.set(true)
                    throw cancellation
                }
                ProcessorResult.Success(context)
            }),
        ) { application, processor ->
            processor.process(backgroundContext(this, config))
            runCurrent()
            assertTrue(entered.isCompleted)

            val shutdown = launch { application.stop() }
            runCurrent()
            assertFalse(shutdown.isCompleted, "Stop must drain accepted work instead of cancelling at once")
            assertFalse(cancelled.get())

            release.complete(Unit)
            runCurrent()
            assertTrue(shutdown.isCompleted)
            assertFalse(cancelled.get(), "Work that finished inside the grace is never cancelled")
            tasks.single().join()
        }
    }

    @Test
    fun `shutdown after the grace cancels unfinished work and repeated stop is idempotent`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Boolean>()
        val cancelled = AtomicBoolean(false)
        val provider = PolicyTrackingProvider()
        val tasks = mutableListOf<BackgroundTask>()
        val config = recordingPolicyConfig { tasks += assertIs<BackgroundAdmission.Accepted>(it).task }
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { context ->
                context.streamingResources.register(provider)
                entered.complete(Unit)
                var cancelledHere = false
                try {
                    awaitCancellation()
                } catch (cancellation: CancellationException) {
                    cancelledHere = true
                    cancelled.set(true)
                    throw cancellation
                } finally {
                    finalized.complete(cancelledHere)
                }
            }),
            background = BackgroundExecutionConfig(shutdownGraceMillis = 1_000),
        ) { application, processor ->
            processor.process(backgroundContext(this, config))
            runCurrent()
            assertTrue(entered.isCompleted)
            assertFalse(provider.isClosed)

            val shutdown = launch { application.stop() }
            runCurrent()
            assertFalse(shutdown.isCompleted, "Stop waits for the configured grace before cancelling")
            assertFalse(cancelled.get())

            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(shutdown.isCompleted, "Stop joins finalization before releasing resources")
            assertTrue(finalized.await())
            assertTrue(provider.isClosed)
            assertEquals(1, provider.releases)

            application.stop()
            assertEquals(1, provider.releases, "Repeated stop releases claims once")
            tasks.single().join()
        }
    }

    @Test
    fun `a provider cleanup failure neither skips later cleanup nor leaks capacity`() = runTest {
        val failing = FailingCleanupProvider()
        val healthy = PolicyTrackingProvider()
        val tasks = mutableListOf<BackgroundTask>()
        val config = recordingPolicyConfig { tasks += assertIs<BackgroundAdmission.Accepted>(it).task }
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { context ->
                context.streamingResources.register(failing)
                context.streamingResources.register(healthy)
                ProcessorResult.Success(context)
            }),
            background = BackgroundExecutionConfig(maxOutstandingTasks = 1),
        ) { _, processor ->
            processor.process(backgroundContext(this, config))
            runCurrent()
            tasks.single().join()

            assertTrue(healthy.isClosed)
            assertEquals(1, healthy.releases)
            // The reservation was released even though one provider failed to clean up.
            assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
        }
    }

    @Test
    fun `a configured default deadline applies to a declaration without its own timeout`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Boolean>()
        val tasks = mutableListOf<BackgroundTask>()
        val config = recordingPolicyConfig { tasks += assertIs<BackgroundAdmission.Accepted>(it).task }
        withBackgroundPolicy(
            this,
            config = config,
            actions = mapOf("branch" to { context ->
                entered.complete(Unit)
                var cancelled = false
                try {
                    awaitCancellation()
                } catch (cancellation: CancellationException) {
                    cancelled = true
                    throw cancellation
                } finally {
                    finalized.complete(cancelled)
                }
            }),
            background = BackgroundExecutionConfig(defaultTimeoutMillis = 40),
        ) { _, processor ->
            processor.process(backgroundContext(this, config))
            runCurrent()
            assertTrue(entered.isCompleted)

            advanceTimeBy(41)
            runCurrent()
            tasks.single().join()
            assertTrue(finalized.await(), "The configured default deadline cancelled the branch")
        }
    }

    @Test
    fun `a failing diagnostic sink does not gate finalization, cleanup or capacity release`() = runTest {
        val tasks = mutableListOf<BackgroundTask>()
        val healthy = PolicyTrackingProvider()
        val failingCleanup = FailingCleanupProvider()
        val config = recordingPolicyConfig { tasks += assertIs<BackgroundAdmission.Accepted>(it).task }
        val capture = BackgroundLogCapture(failOnAppend = true)
        try {
            withBackgroundPolicy(
                this,
                config = config,
                actions = mapOf("branch" to { context ->
                    context.streamingResources.register(failingCleanup)
                    context.streamingResources.register(healthy)
                    ProcessorResult.Success(context)
                }),
                background = BackgroundExecutionConfig(maxOutstandingTasks = 1),
            ) { _, processor ->
                processor.process(backgroundContext(this, config))
                runCurrent()
                withTimeout(1_000) { tasks.single().join() }

                assertTrue(healthy.isClosed, "Later cleanup still ran")
                assertEquals(1, healthy.releases)
                // The capacity slot is reusable despite the cleanup failure and the dead sink.
                assertIs<ProcessorResult.Success>(processor.process(backgroundContext(this, config)))
            }
        } finally {
            capture.close()
        }
    }

    @Test
    fun `background execution defaults match the approved values`() {
        val config = BackgroundExecutionConfig()
        assertEquals(1024, config.maxOutstandingTasks)
        assertNull(config.defaultTimeoutMillis)
        assertEquals(30_000, config.shutdownGraceMillis)
    }

    @Test
    fun `invalid background limits fail before the application starts`() {
        val invalid =
            listOf(
                BackgroundExecutionConfig(maxOutstandingTasks = 0),
                BackgroundExecutionConfig(maxOutstandingTasks = -1),
                BackgroundExecutionConfig(defaultTimeoutMillis = 0),
                BackgroundExecutionConfig(defaultTimeoutMillis = -1),
                BackgroundExecutionConfig(shutdownGraceMillis = -1),
            )
        invalid.forEach { background ->
            assertFailsWith<IllegalArgumentException> {
                bootstrap(
                    MuseApplicationConfig(
                        flows = emptyList(),
                        factory = policyFactory(),
                        background = background,
                    ),
                )
            }
        }
    }

    @Test
    fun `a background handle from another binding is a contract defect`() = runTest {
        lateinit var foreign: BackgroundHandle
        val first = ProbePolicyConfig { definition, _ ->
            definition.inline(listOf(CaptureConfig("branch", "branch")))
            foreign = definition.background()
            PolicyFactory { ExecutionPolicy { PolicyOutcome.Success(it.input) } }
        }
        val second =
            ProbePolicyConfig { definition, _ ->
                val body = definition.inline(listOf(CaptureConfig("branch", "branch")))
                PolicyFactory {
                    ExecutionPolicy { invocation ->
                        invocation.submit(foreign, body)
                        PolicyOutcome.Success(invocation.input)
                    }
                }
            }
        withBackgroundPolicy(this, first, actions = emptyMap()) { _, _ -> }
        withBackgroundPolicy(this, second, actions = emptyMap()) { _, processor ->
            assertFailsWith<IllegalArgumentException> { processor.process(backgroundContext(this, second)) }
        }
    }
}

private fun asyncPolicyConfig(
    maxConcurrency: Int? = null,
    timeoutMillis: Long? = null,
): ProbePolicyConfig =
    ProbePolicyConfig { definition, _ ->
        val body = definition.inline(listOf(CaptureConfig("branch", "branch")))
        val background = definition.background(maxConcurrency, timeoutMillis)
        PolicyFactory { AsyncPolicy(background, body) }
    }

private fun recordingPolicyConfig(
    onAdmission: (BackgroundAdmission) -> Unit,
): ProbePolicyConfig =
    ProbePolicyConfig { definition, _ ->
        val body = definition.inline(listOf(CaptureConfig("branch", "branch")))
        val background = definition.background()
        PolicyFactory { RecordingAsyncPolicy(background, body, onAdmission) }
    }

private class RecordingAsyncPolicy(
    private val background: BackgroundHandle,
    private val body: BodyHandle,
    private val onAdmission: (BackgroundAdmission) -> Unit,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome {
        val admission = invocation.submit(background, body)
        onAdmission(admission)
        return when (admission) {
            is BackgroundAdmission.Accepted -> PolicyOutcome.Success(invocation.input)
            is BackgroundAdmission.Rejected -> error("Admission was rejected: ${admission.reason}")
        }
    }
}

private suspend fun <T> withBackgroundPolicy(
    scope: TestScope,
    config: Router,
    actions: Map<String, suspend (ExecutionContext) -> ProcessorResult>,
    background: BackgroundExecutionConfig = BackgroundExecutionConfig(),
    block: suspend (MuseApplication, MuseProcessor) -> T,
): T {
    val flow = policyFlow(listOf(config))
    val application =
        bootstrap(
            config =
                MuseApplicationConfig(
                    flows = listOf(flow),
                    factory = policyFactory(actions),
                    background = background,
                ),
            dispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    return try {
        application.init()
        block(application, application.context.policyBindings!!.pipeline(flow).elements.single().processor)
    } finally {
        application.stop()
    }
}

/**
 * Builds the request context the way the engine does: the policy component frame that
 * `invokeTerminal` pushes, and (for [submittingContext]) the incoming event's provider graph
 * registered with the request's own execution registry before the policy may submit it.
 */
private fun backgroundContext(
    scope: CoroutineScope,
    config: Router,
    event: MuseEvent = MuseEvent(),
): ExecutionContext = policyContext(scope, event).pushFrame(config, 0)

private fun submittingContext(
    scope: CoroutineScope,
    config: Router,
    event: MuseEvent,
): ExecutionContext =
    backgroundContext(scope, config, event).also { context ->
        StreamingEventResources.registerEventProviders(context.event, context.streamingResources)
    }

/**
 * Asserts the single terminal diagnostic of one branch: task id, correlation id, component id
 * and the error-type classification.
 */
private fun assertTerminalDiagnostic(
    events: List<ILoggingEvent>,
    correlationId: String,
    expectedType: String,
) {
    val records = events.filter { it.formattedMessage.contains(correlationId) }
    assertEquals(1, records.size, "Exactly one terminal diagnostic is expected")
    val message = records.single().formattedMessage
    assertTrue(message.contains(expectedType), message)
    assertTrue(Regex("Background task [0-9a-fA-F-]{36} ").containsMatchIn(message), message)
    assertTrue(message.contains("on component probe"), message)
}

private class PreparationDefect(message: String) : RuntimeException(message)

/** A supported container whose traversal fails, used to prove reservation rollback. */
private class ExplodingList : AbstractList<Any?>() {
    override val size: Int
        get() = 1

    override fun get(index: Int): Any? = throw PreparationDefect("traversal defect")
}

/** Provider whose backing transport must be detached before background use. */
private class PreparingProvider(
    private val prepare: suspend () -> Unit,
) : MuseCursorStreamProvider, BackgroundStreamPreparation {
    val preparations = AtomicInteger()
    val releases = AtomicInteger()
    @Volatile override var isClosed = false
        private set

    override fun openCursor(): MuseCursorStream = error("This fixture does not open cursors")

    override fun close() {
        isClosed = true
    }

    override fun releaseResources() {
        releases.incrementAndGet()
    }

    override suspend fun prepareForBackgroundUse() {
        preparations.incrementAndGet()
        prepare()
    }
}

private class FailingCleanupProvider : MuseCursorStreamProvider {
    override val isClosed: Boolean = false

    override fun openCursor(): MuseCursorStream = error("This fixture does not open cursors")

    override fun close() {
        throw IllegalStateException("close failed")
    }

    override fun releaseResources() {
        throw IllegalStateException("releaseResources failed")
    }
}

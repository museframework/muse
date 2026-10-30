package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.AsyncConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.policy.BackgroundAdmission
import dev.museframework.engine.api.execution.policy.BackgroundTask
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.execution.policy.asyncPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncPolicyLifecycleIT {

    @Test
    fun `stream input stays readable after the reply while the branch still runs`() = runTest {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("later".toByteArray())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = CompletableDeferred<String>()
        val factory =
            asyncFactory(
                actions =
                    mapOf(
                        "branch" to
                            { context: ExecutionContext ->
                                entered.complete(Unit)
                                release.await()
                                read.complete(context.streamText())
                                ProcessorResult.Success(context)
                            },
                    ),
            )
        val flow = policyFlow(listOf(asyncBlock(listOf(CaptureConfig(docName = "branch", docId = "branch")))))
        val session = EngineFlowSession.open(this, flow, factory)
        try {
            val envelope = MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of(provider as Any)))
            session.channel.send(envelope)
            runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue(envelope.replyTo.isCompleted)
            assertFalse(read.isCompleted)
            assertFalse(provider.isClosed)
            release.complete(Unit)
            runCurrent()
            assertEquals("later", read.await())
        } finally {
            session.close()
        }
    }

    @Test
    fun `explicit cancel before the reply leaves the request reply unchanged`() = runTest {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("later".toByteArray())
        val branchEntered = CompletableDeferred<Unit>()
        val branchCancelled = CompletableDeferred<Boolean>()
        val requestEntered = CompletableDeferred<Unit>()
        val requestRelease = CompletableDeferred<Unit>()
        val task = CompletableDeferred<BackgroundTask>()
        val factory =
            asyncFactory(
                actions =
                    mapOf(
                        "branch" to
                            { context: ExecutionContext ->
                                branchEntered.complete(Unit)
                                var cancelled = false
                                try {
                                    awaitCancellation()
                                } catch (cancellation: CancellationException) {
                                    cancelled = true
                                    throw cancellation
                                } finally {
                                    branchCancelled.complete(cancelled)
                                }
                            },
                        "request-tail" to
                            { context: ExecutionContext ->
                                requestEntered.complete(Unit)
                                requestRelease.await()
                                ProcessorResult.Success(context)
                            },
                    ),
                onTask = { task.complete(it) },
            )
        val flow =
            policyFlow(
                listOf(
                    asyncBlock(listOf(CaptureConfig(docName = "branch", docId = "branch"))),
                    CaptureConfig(docName = "request tail", docId = "request-tail"),
                ),
            )
        val session = EngineFlowSession.open(this, flow, factory)
        try {
            val envelope = MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of(provider as Any)))
            session.channel.send(envelope)
            runCurrent()
            assertTrue(branchEntered.isCompleted)
            assertTrue(requestEntered.isCompleted)
            assertFalse(envelope.replyTo.isCompleted)

            val accepted = task.await()
            accepted.cancel()
            accepted.join()
            assertTrue(branchCancelled.await(), "Cancelling the task reaches the accepted branch")
            assertFalse(envelope.replyTo.isCompleted, "Cancelling a task never rewrites the request")
            assertFalse(provider.isClosed, "The request still owns its claim")

            requestRelease.complete(Unit)
            runCurrent()
            assertTrue(envelope.replyTo.isCompleted)
        } finally {
            session.close()
        }
    }

    @Test
    fun `explicit cancel after the reply releases the branch's claims`() = runTest {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("later".toByteArray())
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Boolean>()
        val task = CompletableDeferred<BackgroundTask>()
        val factory =
            asyncFactory(
                actions =
                    mapOf(
                        "branch" to
                            { _: ExecutionContext ->
                                entered.complete(Unit)
                                var cancelledHere = false
                                try {
                                    awaitCancellation()
                                } catch (cancellation: CancellationException) {
                                    cancelledHere = true
                                    throw cancellation
                                } finally {
                                    cancelled.complete(cancelledHere)
                                }
                            },
                    ),
                onTask = { task.complete(it) },
            )
        val flow = policyFlow(listOf(asyncBlock(listOf(CaptureConfig(docName = "branch", docId = "branch")))))
        val session = EngineFlowSession.open(this, flow, factory)
        try {
            val envelope = MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of(provider as Any)))
            session.channel.send(envelope)
            runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue(envelope.replyTo.isCompleted)
            // The finished request released its own claim; only the accepted branch keeps the input alive.
            assertFalse(provider.isClosed)

            val accepted = task.await()
            accepted.cancel()
            accepted.join()
            assertTrue(cancelled.await())
            assertTrue(provider.isClosed, "The last claim is released when the cancelled branch finalizes")
        } finally {
            session.close()
        }
    }

    @Test
    fun `the running limit is shared across requests of one prepared policy`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val factory =
            asyncFactory(
                actions =
                    mapOf(
                        "branch" to
                            { context: ExecutionContext ->
                                when (context.event.payload?.value) {
                                    "first" -> {
                                        firstEntered.complete(Unit)
                                        firstRelease.await()
                                    }
                                    "second" -> secondEntered.complete(Unit)
                                }
                                ProcessorResult.Success(context)
                            },
                    ),
            )
        val flow =
            policyFlow(
                listOf(
                    asyncBlock(
                        listOf(CaptureConfig(docName = "branch", docId = "branch")),
                        maxConcurrency = 1,
                    ),
                ),
            )
        val session = EngineFlowSession.open(this, flow, factory)
        try {
            session.channel.send(MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of("first"))))
            runCurrent()
            assertTrue(firstEntered.isCompleted)

            session.channel.send(MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of("second"))))
            runCurrent()
            assertFalse(secondEntered.isCompleted, "The prepared operator's limit is shared by both requests")

            firstRelease.complete(Unit)
            runCurrent()
            assertTrue(secondEntered.isCompleted)
        } finally {
            session.close()
        }
    }

    @Test
    fun `local recovery inside a branch stays local`() = runTest {
        val markers = mutableListOf<String>()
        val failure = MuseError("branch failure", "branch failure", ErrorType("APP", "BRANCH", ErrorType.Mule.ANY), IllegalStateException("branch failure"))
        val factory =
            asyncFactory(
                actions =
                    mapOf(
                        "failing" to
                            { context: ExecutionContext ->
                                markers += "failing"
                                ProcessorResult.Failure(failure, context)
                            },
                        "recovered" to
                            { context: ExecutionContext ->
                                markers += "recovered"
                                ProcessorResult.Success(context)
                            },
                        "after-try" to
                            { context: ExecutionContext ->
                                markers += "after-try"
                                ProcessorResult.Success(context)
                            },
                        "flow-handler" to
                            { context: ExecutionContext ->
                                markers += "flow-handler"
                                ProcessorResult.Success(context)
                            },
                    ),
            )
        factory.registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
        val flow =
            policyFlow(
                listOf(
                    asyncBlock(
                        listOf(
                            TryConfig(
                                docName = "try",
                                docId = "try",
                                pipeline = listOf(CaptureConfig(docName = "failing", docId = "failing")),
                                errorHandler =
                                    ErrorHandler(
                                        listOf(
                                            ErrorHandlerStrategy.OnErrorContinue(
                                                ErrorType.Mule.ANY,
                                                listOf(CaptureConfig(docName = "recovered", docId = "recovered")),
                                            ),
                                        ),
                                    ),
                            ),
                            CaptureConfig(docName = "after try", docId = "after-try"),
                        ),
                    ),
                ),
                handler =
                    ErrorHandler(
                        listOf(
                            ErrorHandlerStrategy.OnErrorContinue(
                                ErrorType.Mule.ANY,
                                listOf(CaptureConfig(docName = "flow handler", docId = "flow-handler")),
                            ),
                        ),
                    ),
            )
        val session = EngineFlowSession.open(this, flow, factory)
        try {
            val envelope = MuseEventEnvelope(event = MuseEvent())
            session.channel.send(envelope)
            runCurrent()
            assertTrue(envelope.replyTo.isCompleted)
            assertEquals(listOf("failing", "recovered", "after-try"), markers)
        } finally {
            session.close()
        }
    }

    @Test
    fun `an unhandled branch failure never re-enters the request handler`() = runTest {
        val correlationId = "corr-branch-failure"
        val markers = mutableListOf<String>()
        val failure = MuseError("branch failure", "branch failure", ErrorType("APP", "BRANCH", ErrorType.Mule.ANY), IllegalStateException("branch failure"))
        val factory =
            asyncFactory(
                actions =
                    mapOf(
                        "failing" to
                            { context: ExecutionContext ->
                                markers += "failing"
                                ProcessorResult.Failure(failure, context)
                            },
                        "request-tail" to
                            { context: ExecutionContext ->
                                markers += "request-tail"
                                ProcessorResult.Success(context)
                            },
                        "flow-handler" to
                            { context: ExecutionContext ->
                                markers += "flow-handler"
                                ProcessorResult.Success(context)
                            },
                    ),
            )
        val flow =
            policyFlow(
                listOf(
                    asyncBlock(listOf(CaptureConfig(docName = "failing", docId = "failing"))),
                    CaptureConfig(docName = "request tail", docId = "request-tail"),
                ),
                handler =
                    ErrorHandler(
                        listOf(
                            ErrorHandlerStrategy.OnErrorContinue(
                                ErrorType.Mule.ANY,
                                listOf(CaptureConfig(docName = "flow handler", docId = "flow-handler")),
                            ),
                        ),
                    ),
            )
        val capture = BackgroundLogCapture()
        try {
            val session = EngineFlowSession.open(this, flow, factory)
            try {
                val envelope = MuseEventEnvelope(event = MuseEvent(correlationId = correlationId))
                session.channel.send(envelope)
                runCurrent()
                assertTrue(envelope.replyTo.isCompleted)
                assertEquals(setOf("failing", "request-tail"), markers.toSet())
            } finally {
                session.close()
            }
        } finally {
            capture.close()
        }
        val records = capture.events.filter { it.formattedMessage.contains(correlationId) }
        assertEquals(1, records.size, "The branch's terminal failure is reported exactly once")
        assertTrue(records.single().formattedMessage.contains("APP:BRANCH"))
    }

    @Test
    fun `shutdown cancels unfinished work, joins finalizers and repeats idempotently`() = runTest {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("later".toByteArray())
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Boolean>()
        val factory =
            asyncFactory(
                actions =
                    mapOf(
                        "branch" to
                            { _: ExecutionContext ->
                                entered.complete(Unit)
                                var cancelledHere = false
                                try {
                                    awaitCancellation()
                                } catch (cancellation: CancellationException) {
                                    cancelledHere = true
                                    throw cancellation
                                } finally {
                                    cancelled.complete(cancelledHere)
                                }
                            },
                    ),
            )
        val flow = policyFlow(listOf(asyncBlock(listOf(CaptureConfig(docName = "branch", docId = "branch")))))
        val session = EngineFlowSession.open(this, flow, factory)
        var closed = false
        try {
            val envelope = MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of(provider as Any)))
            session.channel.send(envelope)
            runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue(envelope.replyTo.isCompleted)
            assertFalse(provider.isClosed)

            session.close()
            closed = true
            assertTrue(cancelled.await(), "Shutdown cancels work that outlives the grace period")
            assertTrue(provider.isClosed, "Shutdown joins finalization before releasing resources")

            session.close()
            assertTrue(provider.isClosed)
        } finally {
            if (!closed) session.close()
        }
    }
}

internal fun asyncFactory(
    actions: Map<String, suspend (ExecutionContext) -> ProcessorResult>,
    onTask: ((BackgroundTask) -> Unit)? = null,
): MuseComponentFactory =
    policyFactory(actions).also { factory ->
        factory.registerPolicyRouter(
            AsyncConfig::class,
            if (onTask == null) {
                asyncPolicyDeclaration
            } else {
                recordingAsyncDeclaration(onTask)
            },
        )
    }

internal fun recordingAsyncDeclaration(
    onTask: (BackgroundTask) -> Unit,
): PolicyRouterDeclaration<AsyncConfig> =
    PolicyRouterDeclaration { component, definition, _ ->
        val body = definition.inline(component.pipeline)
        val background = definition.background(maxConcurrency = component.maxConcurrency)
        PolicyFactory { RecordingAsyncPolicy(background, body, onTask) }
    }

private class RecordingAsyncPolicy(
    private val background: dev.museframework.engine.api.execution.policy.BackgroundHandle,
    private val body: BodyHandle,
    private val onTask: (BackgroundTask) -> Unit,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome =
        when (val admission = invocation.submit(background, body)) {
            is BackgroundAdmission.Accepted -> {
                onTask(admission.task)
                PolicyOutcome.Success(invocation.input)
            }
            is BackgroundAdmission.Rejected -> error("Admission was rejected: ${admission.reason}")
        }
}

internal fun asyncBlock(
    body: List<PipelineElement>,
    maxConcurrency: Int? = null,
): AsyncConfig = AsyncConfig(docName = "async", docId = "async", pipeline = body, maxConcurrency = maxConcurrency)

internal fun ExecutionContext.streamText(): String {
    val payload = event.payload?.value
    check(payload is MuseCursorStreamProvider) { "Expected a stream provider payload" }
    return payload.openCursor().use { it.readBytes().toString(Charsets.UTF_8) }
}

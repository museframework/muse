package dev.museframework.engine.integration

import dev.museframework.dsl.dsl.async
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.`try`
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.factory.MuseEngineRegistrar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.Collections
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Observes source-owned resource lifetime, request cancellation and shutdown ordering. */
class SourceCancellationLifecycleIT {
    @Test
    fun `the root reply does not close a stream an accepted async branch still reads`() =
        runTest {
            val provider = RepeatableInMemoryCursorStreamProvider.fromBytes(BRANCH_CONTENT.toByteArray())
            val markers = mutableListOf<String>()
            val branchEntered = CompletableDeferred<Unit>()
            val branchRelease = CompletableDeferred<Unit>()

            val flow =
                flow("async-branch-stream-lifetime") {
                    async {
                        docName = "Async"
                        processor(CaptureConfig(docName = BRANCH_READER, docId = "delayed-branch-reader"))
                    }
                    processor(CaptureConfig(docName = ROOT_STEP, docId = "root-reply"))
                }

            val factory =
                MuseComponentFactory().apply {
                    MuseEngineRegistrar().register(this)
                    registerProcessor(CaptureConfig::class) { component, _ ->
                        CaptureMuseProcessor(component) { context ->
                            when (component.docName) {
                                ROOT_STEP -> {
                                    markers += "root-reply"
                                    ProcessorResult.Success(
                                        context.withEvent(
                                            context.event.copy(payload = TypedValue.of(ROOT_REPLY)),
                                        ),
                                    )
                                }

                                else -> {
                                    branchEntered.complete(Unit)
                                    branchRelease.await()
                                    try {
                                        val content = provider.openCursor().use { it.readBytes().decodeToString() }
                                        markers += "branch-read:$content"
                                    } catch (failure: Throwable) {
                                        markers += "branch-read-failed:${failure::class.simpleName}"
                                    }
                                    ProcessorResult.Success(context)
                                }
                            }
                        }
                    }
                }

            val session = EngineFlowSession.open(this, flow, factory)
            try {
                val envelope = MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of(provider as Any)))
                session.channel.send(envelope)
                runCurrent()

                assertTrue(branchEntered.isCompleted, "The branch was accepted before the root reply")
                assertTrue(envelope.replyTo.isCompleted)
                assertNull(envelope.replyTo.getCompleted().error)
                assertFalse(
                    provider.isClosed,
                    "The root reply must not close a stream an accepted branch still owns; markers=$markers",
                )
                assertEquals(listOf("root-reply"), markers)

                branchRelease.complete(Unit)
                runCurrent()

                assertEquals(listOf("root-reply", "branch-read:$BRANCH_CONTENT"), markers)
            } finally {
                session.close()
            }
        }

    @Test
    fun `a source cancellation signal cancels request execution and still releases its claims`() =
        runTest {
            val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("cancelled".toByteArray())
            val entered = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Boolean>()
            val signal = CompletableDeferred<Unit>()

            val flow =
                flow("request-cancellation") {
                    processor(CaptureConfig(docName = NEVER_FINISHES, docId = "never-finishing"))
                }
            val factory =
                MuseComponentFactory().apply {
                    MuseEngineRegistrar().register(this)
                    registerProcessor(CaptureConfig::class) { component, _ ->
                        CaptureMuseProcessor(component) { context ->
                            entered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                cancellationObserved.complete(
                                    context.coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.isCancelled == true,
                                )
                            }
                        }
                    }
                }

            val session = EngineFlowSession.open(this, flow, factory)
            try {
                val envelope =
                    MuseEventEnvelope(
                        event = MuseEvent(payload = TypedValue.of(provider as Any)),
                        requestCancellation = signal,
                    )
                session.channel.send(envelope)
                runCurrent()
                assertTrue(entered.isCompleted)
                assertFalse(envelope.replyTo.isCompleted)

                signal.complete(Unit)
                runCurrent()

                assertTrue(cancellationObserved.await(), "The engine request job observes the source's signal")
                assertTrue(envelope.replyTo.isCompleted, "A cancelled request never leaves its source waiting")
                val failure = runCatching { envelope.replyTo.await() }.exceptionOrNull()
                assertTrue(
                    failure is CancellationException,
                    "The reply reports the cancellation, was ${failure?.let { it::class.simpleName }}",
                )
                assertTrue(
                    provider.isClosed,
                    "Request finalization is not cancelled with its caller: the claims are released",
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun `stop drains queued events and waits for the request that was already running`() =
        runTest {
            val queuedProvider = RepeatableInMemoryCursorStreamProvider.fromBytes("queued".toByteArray())
            val runningProvider = RepeatableInMemoryCursorStreamProvider.fromBytes("running".toByteArray())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val flow =
                flow("stop-drain") {
                    processor(CaptureConfig(docName = BLOCKING, docId = "blocking"))
                }
            val factory =
                MuseComponentFactory().apply {
                    MuseEngineRegistrar().register(this)
                    registerProcessor(CaptureConfig::class) { component, _ ->
                        CaptureMuseProcessor(component) { context ->
                            entered.complete(Unit)
                            release.await()
                            ProcessorResult.Success(context)
                        }
                    }
                }

            val session = EngineFlowSession.open(this, flow, factory)
            try {
                val running =
                    MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of(runningProvider as Any)))
                session.channel.send(running)
                runCurrent()
                assertTrue(entered.isCompleted)

                val queued =
                    MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of(queuedProvider as Any)))
                session.channel.send(queued)

                // Undispatched: stop closes the queue and drains it before the consumer can
                // receive the event that is still buffered.
                val stopping = async(start = CoroutineStart.UNDISPATCHED) { session.close() }
                runCurrent()

                assertTrue(queuedProvider.isClosed, "A queued event that never executes releases its claims")
                assertFalse(runningProvider.isClosed, "Stop never closes the claims of work still running")
                assertFalse(stopping.isCompleted, "Stop drains the running request for the grace period")

                release.complete(Unit)
                runCurrent()

                assertTrue(stopping.isCompleted, "Stop joins request finalization before releasing resources")
                assertFalse(running.replyTo.isCancelled, "Work that finished inside the grace is not cancelled")
                assertTrue(runningProvider.isClosed)
            } finally {
                session.close()
            }
        }

    @Test
    fun `stop keeps prepared policies alive until an accepted branch has terminated`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val nestedRan = CompletableDeferred<Unit>()

            val flow =
                flow("stop-background-bindings") {
                    async {
                        docName = "Async"
                        processor(CaptureConfig(docName = BLOCKING, docId = "blocking"))
                        // A managed policy the branch only reaches after the barrier: the application
                        // must not release its bindings while this branch is still running.
                        `try` {
                            processor(CaptureConfig(docName = NESTED, docId = "nested"))
                        }
                    }
                    processor(CaptureConfig(docName = ROOT, docId = "root"))
                }
            val factory =
                MuseComponentFactory().apply {
                    MuseEngineRegistrar().register(this)
                    registerProcessor(CaptureConfig::class) { component, _ ->
                        CaptureMuseProcessor(component) { context ->
                            when (component.docName) {
                                BLOCKING -> {
                                    entered.complete(Unit)
                                    release.await()
                                }

                                NESTED -> nestedRan.complete(Unit)
                            }
                            ProcessorResult.Success(context)
                        }
                    }
                }

            val session = EngineFlowSession.open(this, flow, factory)
            try {
                session.channel.send(MuseEventEnvelope(event = MuseEvent()))
                runCurrent()
                assertTrue(entered.isCompleted)

                val stopping = launch { session.close() }
                runCurrent()
                assertFalse(stopping.isCompleted, "Stop waits for the accepted branch inside the grace")

                release.complete(Unit)
                runCurrent()

                nestedRan.await()
                assertTrue(stopping.isCompleted)
            } finally {
                session.close()
            }
        }

    /**
     * Race regression for the receive-versus-publication window: a consumer that has already
     * received an event must publish it before stop snapshots the request ledger, and every event
     * that stop neither executed nor drained must still release its claims.
     */
    @Test
    fun `stop never publishes a request after it releases the application`() =
        runBlocking {
            repeat(RACE_ROUNDS) {
                val factory =
                    MuseComponentFactory().apply {
                        MuseEngineRegistrar().register(this)
                        registerSource(HarnessSourceConfig::class) { component, _, _ -> HarnessMuseSource(component) }
                        registerProcessor(CaptureConfig::class) { component, _ ->
                            CaptureMuseProcessor(component) { context ->
                                yield()
                                ProcessorResult.Success(context)
                            }
                        }
                    }
                val flow =
                    flow("stop-publication-race") {
                        processor(CaptureConfig(docName = RACE_STEP, docId = "race-step"))
                    }.copy(
                        initialState = dev.museframework.dsl.api.model.flow.FlowInitialState.STARTED,
                        source =
                            HarnessSourceConfig(
                                docName = "race source",
                                docId = "race-source",
                            ),
                    )
                val application =
                    dev.museframework.engine.app.bootstrap(
                        dev.museframework.engine.app.MuseApplicationConfig(flows = listOf(flow), factory = factory),
                        Dispatchers.Default,
                    )
                application.init()
                val channel = application.channelFor(flow.name)
                val sent = Collections.synchronizedList(mutableListOf<Pair<MuseEventEnvelope, RepeatableInMemoryCursorStreamProvider>>())

                val sender =
                    launch(Dispatchers.Default) {
                        repeat(RACE_SENDS) {
                            val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("race".toByteArray())
                            val envelope = MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of(provider as Any)))
                            if (channel.trySend(envelope).isSuccess) {
                                sent += envelope to provider
                            } else {
                                // A refused send never became a request: the sender releases its claims.
                                envelope.executionResources.releaseAll()
                            }
                        }
                    }
                // At least one event is published before the queue closes, so the round always
                // races a consumer that has already received an event against stop.
                withTimeout(RACE_TIMEOUT_MILLIS) {
                    while (sent.isEmpty() && sender.isActive) delay(RACE_POLL_MILLIS)
                }
                val stopping = launch(Dispatchers.Default) { application.stop() }
                sender.join()
                withTimeout(RACE_TIMEOUT_MILLIS) { stopping.join() }

                assertTrue(sent.isNotEmpty(), "the race round must publish at least one event")
                sent.forEach { (envelope, provider) ->
                    val failure = runCatching { envelope.replyTo.getCompleted() }.exceptionOrNull()
                    assertFalse(
                        failure?.message.orEmpty().contains("closed"),
                        "no request may reach a released application: ${failure?.message}",
                    )
                    assertTrue(
                        provider.isClosed,
                        "event claims must be released once stop returns (reply failure=${failure?.message})",
                    )
                }
            }
        }

    private companion object {
        const val ROOT_STEP = "Root reply"
        const val BRANCH_READER = "Delayed branch reader"
        const val ROOT_REPLY = "root-reply"
        const val BRANCH_CONTENT = "branch-content"
        const val NEVER_FINISHES = "Never finishing"
        const val BLOCKING = "Blocking"
        const val NESTED = "Nested policy step"
        const val ROOT = "Root step"
        const val RACE_STEP = "Race step"
        const val RACE_ROUNDS = 20
        const val RACE_SENDS = 8
        const val RACE_TIMEOUT_MILLIS = 10_000L
        const val RACE_POLL_MILLIS = 1L
    }
}

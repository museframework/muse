package dev.museframework.engine.execution

import dev.museframework.dsl.api.config.Pipeline
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.ScatterGatherRoute
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.integration.CaptureConfig
import dev.museframework.engine.integration.policyFactory
import dev.museframework.engine.integration.withPreparedPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Observes the declared scatter-gather policy's concurrency and cancellation contracts through a
 * prepared occurrence, with real dispatchers so permits are genuinely concurrent.
 */
class ScatterGatherConcurrencyTest {
    @Test
    fun `scatter gather never executes more routes than max concurrency`() =
        runBlocking {
            val active = AtomicInteger()
            val sequence = AtomicInteger()
            val maximumActive = AtomicInteger()
            val started = Channel<Int>(Channel.UNLIMITED)
            val release = Channel<Unit>(Channel.UNLIMITED)
            val factory =
                policyFactory(
                    mapOf(
                        "route" to { context: ExecutionContext ->
                            val item = sequence.incrementAndGet()
                            val current = active.incrementAndGet()
                            maximumActive.updateAndGet { maximum -> maxOf(maximum, current) }
                            started.send(item)
                            try {
                                release.receive()
                            } finally {
                                active.decrementAndGet()
                            }
                            ProcessorResult.Success(
                                context.withEvent(context.event.copy(payload = TypedValue.of(item))),
                            )
                        },
                    ),
                )
            val config = scatterGather(maxConcurrency = 2, routeCount = 4, timeout = null)

            withPreparedPolicy(config, factory) { _, processor ->
                val execution =
                    async(Dispatchers.Default) {
                        processor.process(policyContext(this, MuseEvent()))
                    }

                val firstBatch =
                    withTimeout(5_000) {
                        setOf(started.receive(), started.receive())
                    }
                assertEquals(2, firstBatch.size)
                assertTrue(firstBatch.all { it in 1..4 })
                assertEquals(2, active.get())
                assertTrue(started.tryReceive().isFailure, "A third route started without a permit")

                repeat(config.routes.size) { release.send(Unit) }
                val result = withTimeout(5_000) { execution.await() }

                assertEquals(2, maximumActive.get())
                val payload = assertIs<ProcessorResult.Success>(result).context.event.payload?.value
                assertEquals(
                    listOf(1, 2, 3, 4),
                    assertIs<List<*>>(payload)
                        .map { assertIs<MuseEvent>(it).payload?.value }
                        .map { (it as Number).toInt() }
                        .sorted(),
                )
            }
        }

    @Test
    fun `scatter gather cancels a timed out route before its post-timeout action`() =
        runBlocking {
            val slowRouteStarted = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            val postTimeoutAction = CompletableDeferred<Unit>()
            val factory =
                policyFactory(
                    mapOf(
                        "fast" to { context: ExecutionContext -> ProcessorResult.Success(context) },
                        "slow" to { context: ExecutionContext ->
                            slowRouteStarted.complete(Unit)
                            try {
                                delay(Long.MAX_VALUE)
                                postTimeoutAction.complete(Unit)
                                ProcessorResult.Success(context)
                            } catch (exception: CancellationException) {
                                cancellationObserved.complete(Unit)
                                throw exception
                            }
                        },
                    ),
                )
            val config =
                ScatterGatherConfig(
                    docName = "Scatter Gather",
                    docId = "scatter-gather",
                    routes =
                        listOf(
                            ScatterGatherRoute(listOf(CaptureConfig("fast", "fast"))),
                            ScatterGatherRoute(listOf(CaptureConfig("slow", "slow"))),
                        ),
                    timeout = 100,
                    maxConcurrency = 2,
                )

            withPreparedPolicy(config, factory) { _, processor ->
                val result =
                    withTimeout(5_000) {
                        processor.process(policyContext(this, MuseEvent()))
                    }

                val failure = assertIs<ProcessorResult.Failure>(result)
                assertEquals(ErrorType.Mule.TIMEOUT, failure.error.errorType)
                withTimeout(5_000) { slowRouteStarted.await() }
                withTimeout(5_000) { cancellationObserved.await() }
                assertFalse(postTimeoutAction.isCompleted)
            }
        }

    private fun scatterGather(
        maxConcurrency: Int?,
        routeCount: Int,
        timeout: Long?,
    ): ScatterGatherConfig =
        ScatterGatherConfig(
            docName = "Scatter Gather",
            docId = "scatter-gather",
            routes =
                (1..routeCount).map { index ->
                    ScatterGatherRoute(listOf(CaptureConfig("route", "route")))
                },
            timeout = timeout,
            maxConcurrency = maxConcurrency,
        )
}

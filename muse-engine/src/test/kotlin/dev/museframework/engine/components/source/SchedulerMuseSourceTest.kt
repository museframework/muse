package dev.museframework.engine.components.source

import dev.museframework.dsl.api.config.CronStrategy
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SchedulerTimeUnit
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.source.MuseEventEnvelope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.SendChannel
import java.util.Properties

class SchedulerMuseSourceTest {

    @Test
    fun `scheduler DSL compiles with fixed frequency`() {
        val flow =
            flow("scheduler-fixed") {
                scheduler {
                    docName = "Scheduler"
                    disallowConcurrentExecution = true
                    fixedFrequency {
                        frequency = "10"
                        startDelay = "1"
                        timeUnit = SchedulerTimeUnit.SECONDS
                    }
                }
            }

        assertEquals("scheduler-fixed", flow.name)
        val schedulerConfig = assertInstanceOf(SchedulerConfig::class.java, flow.source)
        assertEquals("Scheduler", schedulerConfig.docName)
        assertTrue(schedulerConfig.disallowConcurrentExecution)
        val strategy = assertInstanceOf(FixedFrequencyStrategy::class.java, schedulerConfig.schedulingStrategy)
        assertEquals("10", strategy.frequency)
        assertEquals("1", strategy.startDelay)
        assertEquals(SchedulerTimeUnit.SECONDS, strategy.timeUnit)
    }

    @Test
    fun `fixed frequency emits empty scheduler events`() = runTest {
        val source =
            SchedulerMuseSource(
                config = SchedulerConfig(
                    schedulingStrategy = FixedFrequencyStrategy(
                        frequency = "10",
                        timeUnit = SchedulerTimeUnit.MILLISECONDS,
                    ),
                ),
                properties = Properties(),
            )
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)

        val job = launch { source.start(channel) }

        val envelope = withTimeout(500) { channel.receive() }

        assertNull(envelope.event.payload)

        job.cancelAndJoin()
        source.stop()
    }

    @Test
    fun `a refused send releases the envelope it never delivered`() = runTest {
        val source =
            SchedulerMuseSource(
                config = SchedulerConfig(
                    schedulingStrategy = FixedFrequencyStrategy(
                        frequency = "10",
                        timeUnit = SchedulerTimeUnit.MILLISECONDS,
                    ),
                ),
                properties = Properties(),
            )
        val channel = RefusingSendChannel()

        val job = launch { source.start(channel) }
        withTimeout(500) { channel.awaitRefused() }
        job.cancelAndJoin()
        source.stop()

        val refused = checkNotNull(channel.refusedEnvelope)
        assertTrue(
            refused.executionResources.isReleased,
            "A scheduler event that the channel refused must release its execution resources",
        )
    }

    // --- Task 5: Non-Concurrent Execution ---

    @Test
    fun `disallow concurrent execution skips tick while previous result is pending`() = runTest {
        val source =
            SchedulerMuseSource(
                config = SchedulerConfig(
                    disallowConcurrentExecution = true,
                    schedulingStrategy = FixedFrequencyStrategy(
                        frequency = "10",
                        timeUnit = SchedulerTimeUnit.MILLISECONDS,
                    ),
                ),
                properties = Properties(),
            )
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        val first = withTimeout(500) { channel.receive() }
        advanceTimeBy(50)

        assertTrue(channel.isEmpty)

        first.replyTo.complete(MuseEvent())
        advanceTimeBy(20)

        val second = withTimeout(500) { channel.receive() }
        assertNotSame(first, second)

        job.cancelAndJoin()
        source.stop()
    }

    // --- Task 6: Cron Runtime ---

    @Test
    fun `cron strategy rejects invalid expression`() = runTest {
        val source =
            SchedulerMuseSource(
                config = SchedulerConfig(
                    schedulingStrategy = CronStrategy(expression = "not a cron"),
                ),
                properties = Properties(),
            )
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)

        assertThrows(java.text.ParseException::class.java) {
            runBlocking { source.start(channel) }
        }
    }

    @Test
    fun `cron strategy fires at least once`() = runBlocking(Dispatchers.Default) {
        val clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneId.of("UTC"))
        val source =
            SchedulerMuseSource(
                config = SchedulerConfig(
                    schedulingStrategy = CronStrategy(
                        expression = "0/1 * * * * ?",
                    ),
                ),
                properties = Properties(),
                clock = clock,
            )
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        val envelope = withTimeout(5000) { channel.receive() }

        assertNotNull(envelope, "Should fire at least once within 5 seconds")

        job.cancelAndJoin()
        source.stop()
    }

    @Test
    fun `cron strategy does not fire when expression never matches`() = runBlocking(Dispatchers.Default) {
        val clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneId.of("UTC"))
        val source =
            SchedulerMuseSource(
                config = SchedulerConfig(
                    schedulingStrategy = CronStrategy(
                        expression = "0 0 12 31 2 ?",
                    ),
                ),
                properties = Properties(),
                clock = clock,
            )
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        delay(1100)

        assertTrue(channel.isEmpty, "No fire should occur for Feb 31st")
        job.cancel()
        source.stop()
    }
}

/** Forwards to a closed channel while keeping the refused envelope observable. */
private class RefusingSendChannel(
    private val delegate: Channel<MuseEventEnvelope> = Channel<MuseEventEnvelope>(Channel.UNLIMITED).also { it.close() },
) : SendChannel<MuseEventEnvelope> by delegate {
    private val refused = CompletableDeferred<Unit>()

    @Volatile
    var refusedEnvelope: MuseEventEnvelope? = null
        private set

    override fun trySend(element: MuseEventEnvelope): ChannelResult<Unit> {
        refusedEnvelope = element
        val result = delegate.trySend(element)
        if (result.isFailure) refused.complete(Unit)
        return result
    }

    suspend fun awaitRefused() = refused.await()
}

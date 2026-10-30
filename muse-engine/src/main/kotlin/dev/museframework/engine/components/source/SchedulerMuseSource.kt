package dev.museframework.engine.components.source

import dev.museframework.dsl.api.config.CronStrategy
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SchedulerTimeUnit
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.spi.SourceBuilder
import dev.museframework.engine.properties.PropertiesLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.quartz.CronExpression
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.Date
import java.util.Properties
import java.util.TimeZone
import kotlin.math.max

class SchedulerMuseSource(
    private val config: SchedulerConfig,
    private val properties: Properties,
    private val clock: Clock = Clock.systemDefaultZone(),
) : MuseSource {
    override val docName: String = config.docName
    override val docId: String = config.docId
    override val docDescription: String? = config.docDescription
    override val sourceFile: String? = config.sourceFile
    override val sourceLine: Int? = config.sourceLine
    override val sourceClass: String? = config.sourceClass

    private val logger = LoggerFactory.getLogger(SchedulerMuseSource::class.java)
    private var lastAcceptedExecution: CompletableDeferred<MuseEvent>? = null
    @Volatile
    private var stopped = false

    override suspend fun start(channel: SendChannel<MuseEventEnvelope>) {
        stopped = false
        try {
            when (val strategy = config.schedulingStrategy) {
                is FixedFrequencyStrategy -> runFixedFrequency(strategy, channel)
                is CronStrategy -> runCron(strategy, channel)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    override suspend fun stop() {
        stopped = true
    }

    override suspend fun onResult(event: MuseEvent, transactionContext: Any?) {
    }

    private suspend fun runFixedFrequency(
        strategy: FixedFrequencyStrategy,
        channel: SendChannel<MuseEventEnvelope>,
    ) {
        val frequencyMs = strategy.frequency.resolvePositiveLong("frequency")
            .toMillis(strategy.timeUnit)
        val startDelayMs = strategy.startDelay.resolveNonNegativeLong("startDelay")
            .toMillis(strategy.timeUnit)

        if (startDelayMs > 0) {
            delay(startDelayMs)
        }

        while (!stopped && currentCoroutineContext().isActive) {
            emitIfAllowed(channel)
            delay(frequencyMs)
        }
    }

    private suspend fun runCron(
        strategy: CronStrategy,
        channel: SendChannel<MuseEventEnvelope>,
    ) {
        val expression = CronExpression(strategy.expression)
        strategy.timeZone?.let { tz -> expression.timeZone = TimeZone.getTimeZone(tz) }

        var after = Date.from(clock.instant())
        while (!stopped && currentCoroutineContext().isActive) {
            val next = expression.getNextValidTimeAfter(after) ?: return
            delay(max(0L, next.time - clock.millis()))
            emitIfAllowed(channel)
            after = Date(max(next.time, clock.millis()))
        }
    }

    private fun emitIfAllowed(channel: SendChannel<MuseEventEnvelope>) {
        val previous = lastAcceptedExecution
        if (config.disallowConcurrentExecution && previous != null && !previous.isCompleted) {
            logger.info(
                "Flow triggered by scheduler '{}' is already running and disallowConcurrentExecution is true. Execution skipped.",
                docName,
            )
            return
        }

        val envelope = MuseEventEnvelope(event = MuseEvent())
        val result = channel.trySend(envelope)
        if (result.isSuccess) {
            lastAcceptedExecution = envelope.replyTo
        } else {
            // A failed send never became an engine-owned request: the sender still owns the claims.
            releaseQuietly(envelope.executionResources)
            logger.info(
                "Scheduler '{}' execution skipped because the event channel is not accepting events.",
                docName,
            )
        }
    }

    /** Cleanup failures must not stop a scheduler from emitting later events. */
    private fun releaseQuietly(registry: StreamingResourceRegistry) {
        try {
            registry.releaseAll()
        } catch (failure: Throwable) {
            logger.error("Scheduler '{}' failed to release an undelivered event's resources.", docName, failure)
        }
    }

    private fun String.resolvePositiveLong(attribute: String): Long {
        val value = resolve(attribute)
        require(value > 0) { "Scheduler $attribute must be greater than 0, but was $value" }
        return value
    }

    private fun String.resolveNonNegativeLong(attribute: String): Long {
        val value = resolve(attribute)
        require(value >= 0) { "Scheduler $attribute must be greater than or equal to 0, but was $value" }
        return value
    }

    private fun String.resolve(attribute: String): Long {
        val resolved = PropertiesLoader.resolvePlaceholders(this, properties)
        return resolved.toLongOrNull()
            ?: throw IllegalArgumentException(
                "Scheduler $attribute must resolve to a number, but was '$resolved'",
            )
    }

    private fun Long.toMillis(unit: SchedulerTimeUnit): Long =
        when (unit) {
            SchedulerTimeUnit.MILLISECONDS -> this
            SchedulerTimeUnit.SECONDS -> this * 1_000
            SchedulerTimeUnit.MINUTES -> this * 60_000
            SchedulerTimeUnit.HOURS -> this * 3_600_000
            SchedulerTimeUnit.DAYS -> this * 86_400_000
        }
}

val schedulerSourceBuilder: SourceBuilder<SchedulerConfig> =
    SourceBuilder { component, context, _ ->
        SchedulerMuseSource(component, context.properties)
    }

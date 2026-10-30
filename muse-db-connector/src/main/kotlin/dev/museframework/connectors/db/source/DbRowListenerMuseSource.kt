package dev.museframework.connectors.db.source

import dev.museframework.connectors.db.connection.DbConnectionFactory
import dev.museframework.connectors.db.connection.DbPropertyResolver
import dev.museframework.connectors.db.execution.JdbcOperations
import dev.museframework.connectors.db.resolveConfig
import dev.museframework.db.api.source.DbRowListenerConfig
import dev.museframework.dsl.api.config.SchedulerTimeUnit
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.spi.SourceBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Properties

internal class DbRowListenerMuseSource(
    private val config: DbRowListenerConfig,
    connectionFactory: DbConnectionFactory,
    expressionEvaluator: dev.museframework.engine.api.expression.MuseExpressionEvaluator,
    private val properties: Properties,
) : MuseSource {
    override val docName: String = config.docName
    override val docId: String = config.docId
    override val docDescription: String? = config.docDescription
    override val sourceFile: String? = config.sourceFile
    override val sourceLine: Int? = config.sourceLine
    override val sourceClass: String? = config.sourceClass

    private val operations = JdbcOperations(connectionFactory, expressionEvaluator)
    private val propertyResolver = DbPropertyResolver(properties)
    private val logger = LoggerFactory.getLogger(DbRowListenerMuseSource::class.java)
    private val seenIds = linkedSetOf<Any>()

    @Volatile
    private var stopped = false
    private var watermark: Any? = null

    override suspend fun start(channel: SendChannel<MuseEventEnvelope>) {
        stopped = false
        val frequency = resolveMillis(config.schedulingStrategy.frequency, "frequency", positive = true)
        val startDelay = resolveMillis(config.schedulingStrategy.startDelay, "startDelay", positive = false)
        if (startDelay > 0) delay(startDelay)

        while (!stopped && currentCoroutineContext().isActive) {
            try {
                poll(channel)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                logger.error("Database row listener '{}' poll failed", docName.ifBlank { config.table }, failure)
            }
            delay(frequency)
        }
    }

    override suspend fun stop() {
        stopped = true
    }

    override suspend fun onResult(event: MuseEvent, transactionContext: Any?) {
        // The polling loop awaits each envelope directly so watermark advancement is explicit.
    }

    private suspend fun poll(channel: SendChannel<MuseEventEnvelope>) {
        val table = requireQualifiedIdentifier(propertyResolver.resolve(config.table), "table")
        val watermarkColumn = config.watermarkColumn?.let(propertyResolver::resolve)?.let {
            requireIdentifier(it, "watermarkColumn")
        }
        val idColumn = config.idColumn?.let(propertyResolver::resolve)?.let {
            requireIdentifier(it, "idColumn")
        }
        val excludeSeenIds = watermarkColumn != null && idColumn != null && watermark != null && seenIds.isNotEmpty()
        val sql =
            buildString {
                append("SELECT * FROM ").append(table)
                if (watermarkColumn != null && watermark != null) {
                    append(" WHERE ").append(watermarkColumn).append(" > :watermark")
                    if (excludeSeenIds) {
                        append(" OR (").append(watermarkColumn).append(" = :watermark AND ")
                            .append(idColumn).append(" NOT IN (")
                        seenIds.forEachIndexed { index, _ ->
                            if (index > 0) append(", ")
                            append(":seen").append(index)
                        }
                        append("))")
                    }
                }
                if (watermarkColumn != null) {
                    append(" ORDER BY ").append(watermarkColumn)
                    if (idColumn != null) append(", ").append(idColumn)
                }
            }
        val parameters = buildMap {
            if (watermarkColumn != null && watermark != null) {
                put("watermark", watermark)
                if (excludeSeenIds) {
                    seenIds.forEachIndexed { index, id -> put("seen$index", id) }
                }
            }
        }
        val rows = withContext(kotlinx.coroutines.Dispatchers.IO) {
            operations.queryRows(sql, parameters, settings = config.settings)
        }

        for (row in rows) {
            val id = idColumn?.let { row.valueIgnoreCase(it) }
            if (id != null && id in seenIds) continue

            val envelope = MuseEventEnvelope(event = MuseEvent(payload = TypedValue.of(row as Any)))
            channel.send(envelope)
            val result = envelope.replyTo.await()
            if (result.error != null) break
            val rowWatermark = watermarkColumn?.let { row.valueIgnoreCase(it) }
            if (watermarkColumn != null && rowWatermark != watermark) {
                watermark = rowWatermark
                seenIds.clear()
            }
            if (id != null) seenIds.add(id)
        }
    }

    private fun resolveMillis(
        value: String,
        attribute: String,
        positive: Boolean,
    ): Long {
        val resolved = propertyResolver.resolve(value).toLongOrNull()
            ?: throw IllegalArgumentException("$attribute must resolve to a whole number")
        if (positive) require(resolved > 0) { "$attribute must be greater than zero" }
        else require(resolved >= 0) { "$attribute must be greater than or equal to zero" }
        val multiplier =
            when (config.schedulingStrategy.timeUnit) {
                SchedulerTimeUnit.MILLISECONDS -> 1L
                SchedulerTimeUnit.SECONDS -> 1_000L
                SchedulerTimeUnit.MINUTES -> 60_000L
                SchedulerTimeUnit.HOURS -> 3_600_000L
                SchedulerTimeUnit.DAYS -> 86_400_000L
            }
        return Math.multiplyExact(resolved, multiplier)
    }

    private fun requireIdentifier(
        value: String,
        attribute: String,
    ): String {
        require(IDENTIFIER.matches(value)) { "$attribute must be an unquoted SQL identifier" }
        return value
    }

    private fun requireQualifiedIdentifier(
        value: String,
        attribute: String,
    ): String {
        require(value.split('.').all(IDENTIFIER::matches)) { "$attribute must be a qualified unquoted SQL identifier" }
        return value
    }

    private fun Map<String, Any?>.valueIgnoreCase(name: String): Any? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

    private companion object {
        val IDENTIFIER: Regex = Regex("[A-Za-z_][A-Za-z0-9_$]*")
    }
}

internal val dbRowListenerSourceBuilder: SourceBuilder<DbRowListenerConfig> =
    SourceBuilder { component, context, expressionEvaluator ->
        val dbConfig = resolveConfig(component.configRef, context)
        DbRowListenerMuseSource(
            config = component,
            connectionFactory = DbConnectionFactory(dbConfig, context.properties),
            expressionEvaluator = expressionEvaluator,
            properties = context.properties,
        )
    }

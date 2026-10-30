package dev.museframework.db.api.dsl

import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.model.DbQuerySettings
import dev.museframework.db.api.source.DbRowListenerConfig
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.SchedulerTimeUnit
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.builder.FlowBuilder
import java.time.Duration
import java.util.UUID

@MuseDsl
class DbRowListenerBuilder internal constructor(
    private val configRef: DbConfigRef,
) {
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var table: String = ""
    var watermarkColumn: String? = null
    var idColumn: String? = null
    var queryTimeout: Duration = Duration.ZERO
    var fetchSize: Int? = null
    var maxRows: Int? = null
    var frequency: String = "1000"
    var startDelay: String = "0"
    var timeUnit: SchedulerTimeUnit = SchedulerTimeUnit.MILLISECONDS

    fun build(): DbRowListenerConfig {
        val callSite = CallSiteCapture.capture()
        return DbRowListenerConfig(
            configRef = configRef,
            table = table,
            watermarkColumn = watermarkColumn,
            idColumn = idColumn,
            settings =
                DbQuerySettings(
                    queryTimeout = queryTimeout,
                    fetchSize = fetchSize,
                    maxRows = maxRows,
                ),
            schedulingStrategy =
                FixedFrequencyStrategy(
                    frequency = frequency,
                    startDelay = startDelay,
                    timeUnit = timeUnit,
                ),
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
        )
    }
}

fun FlowBuilder.dbListener(config: DbConfig, block: DbRowListenerBuilder.() -> Unit) =
    dbListener(DbConfigRef.ByReference(config), block)

fun FlowBuilder.dbListener(configName: String, block: DbRowListenerBuilder.() -> Unit) =
    dbListener(DbConfigRef.ByName(configName), block)

fun FlowBuilder.dbListener(configRef: DbConfigRef, block: DbRowListenerBuilder.() -> Unit) {
    source(DbRowListenerBuilder(configRef).apply(block).build())
}

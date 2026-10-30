package dev.museframework.babelfish.mappers.db

import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.db.api.source.DbRowListenerConfig
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.SchedulerTimeUnit

object DbRowListenerMapper : SourceMapper<DbRowListenerConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbRowListenerConfig {
        element.rejectUnsupportedAttributes("listener", setOf("useColumnNumbers"))
        val metadata = element.dbMetadata(ctx)
        val fixedFrequency = element.descendants().firstOrNull { it.tag == "fixed-frequency" }
        return DbRowListenerConfig(
            configRef = element.dbConfigRef(),
            table = element.requireAttr("table", element.location),
            watermarkColumn = element.attrs["watermarkColumn"],
            idColumn = element.attrs["idColumn"],
            settings = element.dbSettings(),
            schedulingStrategy =
                FixedFrequencyStrategy(
                    frequency = fixedFrequency?.attrs?.get("frequency") ?: "1000",
                    startDelay = fixedFrequency?.attrs?.get("startDelay") ?: "0",
                    timeUnit =
                        fixedFrequency?.attrs?.get("timeUnit")
                            ?.let { SchedulerTimeUnit.valueOf(it.uppercase()) }
                            ?: SchedulerTimeUnit.MILLISECONDS,
                ),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbRowListenerCodeEmitter : SourceCodeEmitter<DbRowListenerConfig> {
    override fun emit(source: DbRowListenerConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.db.api.dsl.dbListener")
        ctx.addSettingsImports(source.settings)
        if (source.schedulingStrategy.timeUnit != SchedulerTimeUnit.MILLISECONDS) {
            ctx.addImport("dev.museframework.dsl.api.config.SchedulerTimeUnit")
        }
        return buildString {
            appendLine("dbListener(${source.configRef.name().kotlinLiteral()}) {")
            appendLine("    table = ${source.table.kotlinLiteral()}")
            source.watermarkColumn?.let { appendLine("    watermarkColumn = ${it.kotlinLiteral()}") }
            source.idColumn?.let { appendLine("    idColumn = ${it.kotlinLiteral()}") }
            emitSettings(
                source.settings,
                settingNames = setOf("queryTimeout", "fetchSize", "maxRows"),
            )
            if (source.schedulingStrategy.frequency != "1000") {
                appendLine("    frequency = ${source.schedulingStrategy.frequency.kotlinLiteral()}")
            }
            if (source.schedulingStrategy.startDelay != "0") {
                appendLine("    startDelay = ${source.schedulingStrategy.startDelay.kotlinLiteral()}")
            }
            if (source.schedulingStrategy.timeUnit != SchedulerTimeUnit.MILLISECONDS) {
                appendLine("    timeUnit = SchedulerTimeUnit.${source.schedulingStrategy.timeUnit}")
            }
            if (source.docName.isNotBlank()) appendLine("    docName = ${source.docName.kotlinLiteral()}")
            append("}")
        }
    }
}

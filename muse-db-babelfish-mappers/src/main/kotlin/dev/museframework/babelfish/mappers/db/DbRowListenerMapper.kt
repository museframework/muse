package dev.museframework.babelfish.mappers.db

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
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
    override fun emit(source: DbRowListenerConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M(%L)", dbDsl("dbListener"), source.configRef.code())
        addStatement("table = %L", kotlinStringLiteral(source.table))
        source.watermarkColumn?.let { addStatement("watermarkColumn = %L", kotlinStringLiteral(it)) }
        source.idColumn?.let { addStatement("idColumn = %L", kotlinStringLiteral(it)) }
        emitRowSettings(source.settings)
        val schedule = source.schedulingStrategy
        if (schedule.frequency != "1000") addStatement("frequency = %L", kotlinStringLiteral(schedule.frequency))
        if (schedule.startDelay != "0") addStatement("startDelay = %L", kotlinStringLiteral(schedule.startDelay))
        if (schedule.timeUnit != SchedulerTimeUnit.MILLISECONDS) {
            addStatement("timeUnit = %T.%N", SchedulerTimeUnit::class, schedule.timeUnit.name)
        }
        if (source.docName.isNotBlank()) addStatement("docName = %L", kotlinStringLiteral(source.docName))
        endControlFlow()
    }
}

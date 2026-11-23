package dev.museframework.babelfish.mappers.core

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.CronStrategy
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SchedulerTimeUnit
import java.util.UUID

object SchedulerSourceMapper : SourceMapper<SchedulerConfig> {
    override fun map(element: RawElement, ctx: MappingContext): SchedulerConfig {
        val schedulingStrategy = element.children.find { it.tag == "scheduling-strategy" }
        val fixedFrequency = schedulingStrategy?.children?.find { it.tag == "fixed-frequency" }
        val cron = schedulingStrategy?.children?.find { it.tag == "cron" }

        val docName =
            element.attrs["name"] ?: element.attrs["doc:name"] ?: ""
        val docId =
            element.attrs["id"] ?: element.attrs["doc:id"] ?: UUID.randomUUID().toString()

        val disallowConcurrent =
            element.attrs["disallowConcurrentExecution"]?.toBooleanStrictOrNull() ?: false

        val strategy =
            when {
                fixedFrequency != null -> {
                    FixedFrequencyStrategy(
                        frequency = fixedFrequency.attrs["frequency"] ?: "1000",
                        startDelay = fixedFrequency.attrs["startDelay"] ?: "0",
                        timeUnit = fixedFrequency.attrs["timeUnit"]?.let {
                            try {
                                SchedulerTimeUnit.valueOf(it.uppercase())
                            } catch (_: IllegalArgumentException) {
                                SchedulerTimeUnit.MILLISECONDS
                            }
                        } ?: SchedulerTimeUnit.MILLISECONDS,
                    )
                }
                cron != null -> {
                    CronStrategy(
                        expression = cron.attrs["expression"] ?: throw IllegalArgumentException(
                            "Cron expression is required for scheduler '$docName'",
                        ),
                        timeZone = cron.attrs["timeZone"],
                    )
                }
                else -> FixedFrequencyStrategy()
            }

        return SchedulerConfig(
            docName = docName,
            docId = docId,
            disallowConcurrentExecution = disallowConcurrent,
            schedulingStrategy = strategy,
        )
    }
}

object KotlinPoetSchedulerSourceCodeEmitter : SourceCodeEmitter<SchedulerConfig> {
    private val scheduler = MemberName("dev.museframework.dsl.dsl", "scheduler")

    override fun emit(source: SchedulerConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", scheduler)
        addDocumentation(source)
        if (source.disallowConcurrentExecution) {
            addStatement("disallowConcurrentExecution = true")
        }
        when (val strategy = source.schedulingStrategy) {
            is FixedFrequencyStrategy -> {
                beginControlFlow("fixedFrequency")
                addStatement("frequency = %L", kotlinStringLiteral(strategy.frequency))
                addStatement("startDelay = %L", kotlinStringLiteral(strategy.startDelay))
                addStatement("timeUnit = %T.%N", SchedulerTimeUnit::class, strategy.timeUnit.name)
                endControlFlow()
            }

            is CronStrategy -> {
                beginControlFlow("cron")
                addStatement("expression = %L", kotlinStringLiteral(strategy.expression))
                strategy.timeZone?.let { addStatement("timeZone = %L", kotlinStringLiteral(it)) }
                endControlFlow()
            }
        }
        endControlFlow()
    }
}

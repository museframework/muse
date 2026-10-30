package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.CronStrategy
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SchedulerTimeUnit
import com.squareup.kotlinpoet.CodeBlock
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
    override fun emit(source: SchedulerConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.scheduler")

        val codeBlock =
            CodeBlock.builder()
                .addStatement("scheduler {")
                .apply {
                    if (source.docName.isNotEmpty()) {
                        addStatement("    docName = %S", source.docName)
                    }
                    addStatement("    docId = %S", source.docId)
                    if (source.disallowConcurrentExecution) {
                        addStatement("    disallowConcurrentExecution = true")
                    }
                    when (val strategy = source.schedulingStrategy) {
                        is FixedFrequencyStrategy -> {
                            ctx.addImport("dev.museframework.dsl.api.config.SchedulerTimeUnit")
                            addStatement("    fixedFrequency {")
                            addStatement("        frequency = %S", strategy.frequency)
                            addStatement("        startDelay = %S", strategy.startDelay)
                            addStatement(
                                "        timeUnit = SchedulerTimeUnit.%L",
                                strategy.timeUnit.name,
                            )
                            addStatement("    }")
                        }
                        is CronStrategy -> {
                            addStatement("    cron {")
                            addStatement("        expression = %S", strategy.expression)
                            strategy.timeZone?.let {
                                addStatement("        timeZone = %S", it)
                            }
                            addStatement("    }")
                        }
                    }
                }
                .add("}")
                .build()

        return codeBlock.toString()
    }
}

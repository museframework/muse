package dev.museframework.dsl.api.config

import dev.museframework.dsl.api.model.component.Source
import java.util.UUID

data class SchedulerConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val disallowConcurrentExecution: Boolean = false,
    val schedulingStrategy: SchedulingStrategy = FixedFrequencyStrategy(),
) : Source

sealed interface SchedulingStrategy

data class FixedFrequencyStrategy(
    val frequency: String = "1000",
    val startDelay: String = "0",
    val timeUnit: SchedulerTimeUnit = SchedulerTimeUnit.MILLISECONDS,
) : SchedulingStrategy

data class CronStrategy(
    val expression: String,
    val timeZone: String? = null,
) : SchedulingStrategy

enum class SchedulerTimeUnit {
    MILLISECONDS,
    SECONDS,
    MINUTES,
    HOURS,
    DAYS,
}

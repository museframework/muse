package dev.museframework.dsl.builder

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.CronStrategy
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SchedulerTimeUnit
import dev.museframework.dsl.api.config.SchedulingStrategy
import dev.museframework.dsl.api.util.CallSiteCapture
import java.util.UUID

@MuseDsl
class SchedulerBuilder {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var disallowConcurrentExecution: Boolean = false

    private var schedulingStrategy: SchedulingStrategy = FixedFrequencyStrategy()

    fun fixedFrequency(block: FixedFrequencyBuilder.() -> Unit = {}) {
        schedulingStrategy = FixedFrequencyBuilder().apply(block).build()
    }

    fun cron(block: CronBuilder.() -> Unit) {
        schedulingStrategy = CronBuilder().apply(block).build()
    }

    fun build(): SchedulerConfig {
        val callSite = CallSiteCapture.capture()
        return SchedulerConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            disallowConcurrentExecution = disallowConcurrentExecution,
            schedulingStrategy = schedulingStrategy,
        )
    }
}

@MuseDsl
class FixedFrequencyBuilder {
    var frequency: String = "1000"
    var startDelay: String = "0"
    var timeUnit: SchedulerTimeUnit = SchedulerTimeUnit.MILLISECONDS

    fun build(): FixedFrequencyStrategy =
        FixedFrequencyStrategy(
            frequency = frequency,
            startDelay = startDelay,
            timeUnit = timeUnit,
        )
}

@MuseDsl
class CronBuilder {
    var expression: String = ""
    var timeZone: String? = null

    fun build(): CronStrategy =
        CronStrategy(
            expression = expression,
            timeZone = timeZone,
        )
}

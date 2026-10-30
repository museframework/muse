package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.builder.FlowBuilder
import dev.museframework.dsl.builder.SchedulerBuilder

@MuseDsl
fun FlowBuilder.scheduler(block: SchedulerBuilder.() -> Unit = {}) {
    source(SchedulerBuilder().apply(block).build())
}

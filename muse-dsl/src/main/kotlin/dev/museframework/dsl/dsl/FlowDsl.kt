package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.builder.FlowBuilder

@MuseDsl
fun flow(
    name: String,
    block: FlowBuilder.() -> Unit,
): MuseFlow.Flow = FlowBuilder(name).apply(block).build()

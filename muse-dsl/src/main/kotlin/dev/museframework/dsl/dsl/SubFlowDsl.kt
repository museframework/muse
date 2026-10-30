package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.builder.SubFlowBuilder

@MuseDsl
fun subFlow(
    name: String,
    block: SubFlowBuilder.() -> Unit,
): MuseFlow.SubFlow = SubFlowBuilder(name).apply(block).build()

package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.builder.FlowRefBuilder

@MuseDsl
fun PipelineOwner.flowRef(
    flow: MuseFlow,
    block: FlowRefBuilder.() -> Unit,
) {
    router(FlowRefBuilder(FlowRef.ByReference(flow)).apply(block).build())
}

@MuseDsl
fun PipelineOwner.flowRef(
    name: String,
    block: FlowRefBuilder.() -> Unit,
) {
    router(FlowRefBuilder(FlowRef.ByName(name)).apply(block).build())
}

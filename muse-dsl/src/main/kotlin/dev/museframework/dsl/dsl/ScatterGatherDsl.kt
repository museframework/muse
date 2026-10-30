package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.ScatterGatherBuilder

@MuseDsl
fun PipelineOwner.scatterGather(block: ScatterGatherBuilder.() -> Unit) {
    router(ScatterGatherBuilder().apply(block).build())
}

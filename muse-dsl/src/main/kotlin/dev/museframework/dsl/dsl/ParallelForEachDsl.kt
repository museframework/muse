package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.ParallelForEachBuilder

@MuseDsl
fun PipelineOwner.parallelForEach(block: ParallelForEachBuilder.() -> Unit) {
    router(ParallelForEachBuilder().apply(block).build())
}

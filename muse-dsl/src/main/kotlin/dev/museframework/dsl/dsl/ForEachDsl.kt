package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.ForEachBuilder

@MuseDsl
fun PipelineOwner.forEach(block: ForEachBuilder.() -> Unit) {
    router(ForEachBuilder().apply(block).build())
}

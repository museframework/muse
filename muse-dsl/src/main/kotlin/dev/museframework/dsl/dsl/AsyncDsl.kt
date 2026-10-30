package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.AsyncBuilder

@MuseDsl
fun PipelineOwner.async(block: AsyncBuilder.() -> Unit) {
    router(AsyncBuilder().apply(block).build())
}

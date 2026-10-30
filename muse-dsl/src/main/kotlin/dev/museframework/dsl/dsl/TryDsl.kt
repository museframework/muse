package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.TryBuilder

@MuseDsl
fun PipelineOwner.`try`(block: TryBuilder.() -> Unit) {
    router(TryBuilder().apply(block).build())
}

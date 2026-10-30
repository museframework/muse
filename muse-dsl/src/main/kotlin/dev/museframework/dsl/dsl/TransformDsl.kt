package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.TransformBuilder

@MuseDsl
fun PipelineOwner.transform(block: TransformBuilder.() -> Unit) {
    processor(TransformBuilder().apply(block).build())
}

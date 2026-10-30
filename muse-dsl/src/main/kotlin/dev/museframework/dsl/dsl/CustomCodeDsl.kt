package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.CustomCodeBuilder

@MuseDsl
fun PipelineOwner.customCode(block: CustomCodeBuilder.() -> Unit) {
    processor(CustomCodeBuilder().apply(block).build())
}

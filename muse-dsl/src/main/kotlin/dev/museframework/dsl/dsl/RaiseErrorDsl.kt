package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.RaiseErrorBuilder

@MuseDsl
fun PipelineOwner.raiseError(block: RaiseErrorBuilder.() -> Unit) {
    processor(RaiseErrorBuilder().apply(block).build())
}

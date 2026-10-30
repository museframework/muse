package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.SetPayloadBuilder

@MuseDsl
fun PipelineOwner.setPayload(block: SetPayloadBuilder.() -> Unit) {
    processor(SetPayloadBuilder().apply(block).build())
}

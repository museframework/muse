package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.ChoiceBuilder

@MuseDsl
fun PipelineOwner.choice(block: ChoiceBuilder.() -> Unit) {
    router(ChoiceBuilder().apply(block).build())
}

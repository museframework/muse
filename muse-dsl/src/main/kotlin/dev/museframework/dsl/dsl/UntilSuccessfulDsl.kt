package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.UntilSuccessfulBuilder

@MuseDsl
fun PipelineOwner.untilSuccessful(block: UntilSuccessfulBuilder.() -> Unit) {
    router(UntilSuccessfulBuilder().apply(block).build())
}

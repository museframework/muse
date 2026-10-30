package dev.museframework.apikit.api.dsl

import dev.museframework.apikit.api.builder.ApikitBuilder
import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner

@MuseDsl
fun PipelineOwner.apikit(name: String, block: ApikitBuilder.() -> Unit) {
    router(ApikitBuilder().apply { configRef = ApikitRef.ByName(name) }.apply(block).build())
}

@MuseDsl
fun PipelineOwner.apikit(config: ApikitConfig, block: ApikitBuilder.() -> Unit) {
    router(ApikitBuilder().apply { configRef = ApikitRef.ByReference(config) }.apply(block).build())
}

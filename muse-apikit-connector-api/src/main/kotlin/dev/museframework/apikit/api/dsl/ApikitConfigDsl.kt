package dev.museframework.apikit.api.dsl

import dev.museframework.apikit.api.builder.ApikitConfigBuilder
import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.dsl.api.annotation.MuseDsl

@MuseDsl
fun apikitConfig(block: ApikitConfigBuilder.() -> Unit): ApikitConfig = ApikitConfigBuilder().apply(block).build()

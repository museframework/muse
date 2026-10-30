package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.config.GlobalProperty

@MuseDsl
fun globalProperty(
    name: String,
    value: String,
) = GlobalProperty(
    docName = name,
    name = name,
    value = value,
)

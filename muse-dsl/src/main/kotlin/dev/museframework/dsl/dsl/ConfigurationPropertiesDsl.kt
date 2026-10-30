package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.config.ConfigurationProperties

@MuseDsl
fun configurationProperties(file: String) =
    ConfigurationProperties(
        docName = file,
        file = file,
    )

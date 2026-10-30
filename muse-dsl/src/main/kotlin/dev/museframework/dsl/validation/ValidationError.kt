package dev.museframework.dsl.validation

import dev.museframework.dsl.api.model.component.MuseComponent

data class ValidationError(
    val message: String,
    val component: MuseComponent? = null,
)

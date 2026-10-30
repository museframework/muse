package dev.museframework.engine.api.event

import dev.museframework.engine.api.error.MuseError
import java.util.UUID

data class MuseEvent(
    val payload: TypedValue<Any>? = null,
    val attributes: TypedValue<Any>? = null,
    val variables: Map<String, TypedValue<Any>?> = emptyMap(),
    val error: MuseError? = null,
    val correlationId: String = UUID.randomUUID().toString(),
)

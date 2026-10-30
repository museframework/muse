package dev.museframework.engine.api.execution.policy

import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent

sealed interface PolicyOutcome {
    val event: MuseEvent

    data class Success(override val event: MuseEvent) : PolicyOutcome

    data class Failure(val error: MuseError, override val event: MuseEvent) : PolicyOutcome
}

package dev.museframework.engine.api.source

import dev.museframework.dsl.api.model.component.MuseComponent
import dev.museframework.engine.api.event.MuseEvent
import kotlinx.coroutines.channels.SendChannel

interface MuseSource : MuseComponent {
    suspend fun start(channel: SendChannel<MuseEventEnvelope>)

    suspend fun stop()

    suspend fun onResult(
        event: MuseEvent,
        transactionContext: Any?,
    )
}

package dev.museframework.engine.dataweave

import dev.museframework.engine.api.event.MuseEvent
import java.util.*

data class DataWeaveContext(
    val event: MuseEvent,
    val properties: Properties = Properties(),
) {
    fun withEvent(event: MuseEvent): DataWeaveContext =
        copy(event = event)

    companion object {
        @JvmStatic
        fun from(event: MuseEvent, properties: Properties): DataWeaveContext =
            DataWeaveContext(event, properties)
    }
}

package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class KeyNameIT {

    private val executor = MuseDataWeaveExecutor()

    @Test
    fun `DataWeave keyword key should resolve with bracket notation`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "params" to mapOf("type" to "A")
                ))
            ),
            Properties()
        )

        val result = executor.evaluate("attributes.params['type']", context)

        assertEquals("A", result?.value)
    }

    @Test
    fun `uppercase non-keyword key should resolve with dot notation`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "params" to mapOf("Type" to "A")
                ))
            ),
            Properties()
        )

        val result = executor.evaluate("attributes.params.Type", context)

        assertEquals("A", result?.value)
    }

    @Test
    fun `test auth key`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "headers" to mapOf("Authorization" to "token")
                ))
            ),
            Properties()
        )

        val result = executor.evaluate("attributes.headers.Authorization", context)

        assertEquals("token", result?.value)
    }
}

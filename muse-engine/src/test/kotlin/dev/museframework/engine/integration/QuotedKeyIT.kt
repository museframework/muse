package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class QuotedKeyIT {

    private val executor = MuseDataWeaveExecutor()

    @Test
    fun `test quoted key with type`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "queryParams" to mapOf("type" to "A")
                ))
            ),
            Properties()
        )

        // Test with quoted key: attributes.queryParams."type"
        val result = executor.evaluate("attributes.queryParams.\"type\"", context)
        println("attributes.queryParams.\"type\" = ${result?.value}")
        assertEquals("A", result?.value)
    }

    @Test
    fun `test quoted key in comparison`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "queryParams" to mapOf("type" to "A")
                ))
            ),
            Properties()
        )

        // Test comparison with quoted key
        val result = executor.evaluate("attributes.queryParams.\"type\" == 'A'", context)
        println("attributes.queryParams.\"type\" == 'A' = ${result?.value}")
        assertEquals(true, result?.value)
    }

    @Test
    fun `test quoted key with special chars`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "headers" to mapOf("Content-Type" to "application/json")
                ))
            ),
            Properties()
        )

        // Test with hyphenated key: attributes.headers."Content-Type"
        val result = executor.evaluate("attributes.headers.\"Content-Type\"", context)
        println("attributes.headers.\"Content-Type\" = ${result?.value}")
        assertEquals("application/json", result?.value)
    }
}

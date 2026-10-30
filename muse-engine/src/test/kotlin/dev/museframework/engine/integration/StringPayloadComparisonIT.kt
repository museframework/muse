package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class StringPayloadComparisonIT {

    private val executor = MuseDataWeaveExecutor()

    @Test
    fun `test string payload comparison`() {
        // Payload is a simple string "fail"
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of("fail", "text/plain")
            ),
            Properties()
        )

        // Test comparison
        val result = executor.evaluate("payload == 'fail'", context)
        println("payload == 'fail' with string payload: ${result?.value}")
        assertEquals(true, result?.value)
    }

    @Test
    fun `string payload should compare equal with double-quoted literal`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of("fail")
            ),
            Properties()
        )

        val result = executor.evaluate("payload == \"fail\"", context)

        assertEquals(true, result?.value)
    }
}

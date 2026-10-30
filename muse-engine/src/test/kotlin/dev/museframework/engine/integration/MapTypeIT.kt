package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class MapTypeIT {

    private val executor = MuseDataWeaveExecutor()

    @Test
    fun `test with single entry map`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "queryParams" to mapOf("type" to "A")  // Single entry
                ))
            ),
            Properties()
        )

        val result = executor.evaluate("""attributes.queryParams."type" """, context)
        println("Single entry: ${result?.value}")
        assertEquals("A", result?.value)
    }

    @Test
    fun `test with multi entry map`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "queryParams" to mapOf("type" to "A", "id" to "123")  // Multiple entries
                ))
            ),
            Properties()
        )

        val result = executor.evaluate("""attributes.queryParams."type" """, context)
        println("Multi entry: ${result?.value}")
        assertEquals("A", result?.value)
    }

    @Test
    fun `test with java hashmap`() {
        val queryParams = java.util.HashMap<String, String>()
        queryParams["type"] = "A"
        
        val attributes = java.util.HashMap<String, Any>()
        attributes["queryParams"] = queryParams

        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(attributes)
            ),
            Properties()
        )

        val result = executor.evaluate("""attributes.queryParams."type" """, context)
        println("Java HashMap: ${result?.value}")
        assertEquals("A", result?.value)
    }
}

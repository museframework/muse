package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.Properties

class NestedAttributesIT {

    private val executor = MuseDataWeaveExecutor()

    private fun createContextWithNestedAttributes(): DataWeaveContext {
        return DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "queryParams" to mapOf("type" to "A", "id" to "123"),
                    "headers" to mapOf("Authorization" to "Bearer token")
                ))
            ),
            Properties()
        )
    }

    @Test
    fun `should access nested queryParams map`() {
        val context = createContextWithNestedAttributes()
        val result = executor.evaluate("attributes.queryParams", context)
        
        assertNotNull(result?.value)
        println("attributes.queryParams = ${result?.value}")
    }

    @Test
    fun `should access nested queryParams type with dot notation`() {
        val context = createContextWithNestedAttributes()
        val result = executor.evaluate("""attributes.queryParams."type" """, context)
        
        assertEquals("A", result?.value)
        println("attributes.queryParams.\"type\" = ${result?.value}")
    }

    @Test
    fun `should access nested queryParams type with bracket notation`() {
        val context = createContextWithNestedAttributes()
        val result = executor.evaluate("attributes.queryParams['type']", context)
        
        assertEquals("A", result?.value)
        println("attributes.queryParams['type'] = ${result?.value}")
    }

    @Test
    fun `should evaluate comparison expression`() {
        val context = createContextWithNestedAttributes()
        val result = executor.evaluate("""attributes.queryParams."type" == 'A'""", context)
        
        assertEquals(true, result?.value)
        println("attributes.queryParams.\"type\" == 'A' = ${result?.value}")
    }
}

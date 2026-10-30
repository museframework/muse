package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

// Wrapper with public Java-style fields.
class AttributesWrapper(
    val queryParams: Map<String, String>,
    val headers: Map<String, String>
)

class WrapperIT {

    private val executor = MuseDataWeaveExecutor()

    @Test
    fun `should access wrapper fields with dot notation`() {
        val context = DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(
                    AttributesWrapper(
                        queryParams = mapOf("type" to "A"),
                        headers = mapOf("auth" to "token")
                    )
                )
            ),
            Properties()
        )

        val result = executor.evaluate("""attributes.queryParams."type" """, context)
        println("attributes.queryParams.\"type\" = ${result?.value}")
        assertEquals("A", result?.value)
    }
}

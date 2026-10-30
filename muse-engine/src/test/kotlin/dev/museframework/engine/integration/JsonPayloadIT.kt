package dev.museframework.engine.integration

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class JsonPayloadIT {

    private val executor = MuseDataWeaveExecutor()
    private val objectMapper = ObjectMapper()

    @Test
    fun `test JSON parsing to Map`() {
        val jsonString = """{"items": ["a", "b", "c"]}"""
        val jsonMap: Map<String, Any?> =
            objectMapper.readValue(jsonString, object : TypeReference<Map<String, Any?>>() {})

        val context =
            DataWeaveContext.from(
                MuseEvent(
                    payload = TypedValue.of(jsonMap, TypedValue.MIME_JAVA),
                ),
                Properties(),
            )

        val result1 = executor.evaluate("payload['items']", context)
        assertEquals(listOf("a", "b", "c"), result1?.value)

        val result2 = executor.evaluate("payload.\"items\"", context)
        assertEquals(listOf("a", "b", "c"), result2?.value)
    }

    @Test
    fun `should parse raw JSON payload according to its mime type`() {
        val context =
            DataWeaveContext.from(
                MuseEvent(
                    payload =
                        TypedValue.of(
                            """{"items": ["a", "b", "c"]}""",
                            TypedValue.MIME_JSON,
                        ),
                ),
                Properties(),
            )

        val result = executor.evaluate("payload['items']", context)

        assertEquals(listOf("a", "b", "c"), result?.value)
        assertEquals(TypedValue.MIME_JAVA, result?.mimeType)
    }
}

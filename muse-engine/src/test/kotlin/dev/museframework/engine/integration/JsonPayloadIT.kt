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

    /**
     * Experimental characterization probe for C2. This intentionally reports the real
     * DataWeave values, types and metadata; it does not specify a new serializer contract.
     * Remove or replace with behavioral assertions after analyzing the CI evidence.
     */
    @Test
    fun `characterize HTTP response representation from DataWeave and payload evaluation`() {
        val map = linkedMapOf("message" to "Resource not found")
        val javaEvent = MuseEvent(payload = TypedValue.of(map, TypedValue.MIME_JAVA))
        val jsonText = """{"message":"Resource not found"}"""
        val jsonEvent = MuseEvent(payload = TypedValue.of(jsonText, TypedValue.MIME_JSON))
        val mapJsonEvent = MuseEvent(payload = TypedValue.of(map, TypedValue.MIME_JSON))
        val evaluator = dev.museframework.engine.expression.MuseExpressionEvaluatorImpl(executor, Properties())

        fun report(label: String, typed: TypedValue<Any>?) {
            val value = typed?.getOrNull()
            val javaType = value?.javaClass?.name ?: "null"
            val display =
                when (value) {
                    is ByteArray -> value.toString(Charsets.UTF_8)
                    else -> value?.toString() ?: "<null>"
                }.replace('\n', ' ').take(250)
            println(
                "DW_RESPONSE_PROBE|$label|type=$javaType|mime=${typed?.mimeType}|charset=${typed?.charset}|value=$display",
            )
        }

        fun attempt(label: String, run: () -> TypedValue<Any>?) {
            try {
                report(label, run())
            } catch (failure: Exception) {
                val root = generateSequence(failure as Throwable) { it.cause }.last()
                println(
                    "DW_RESPONSE_PROBE|$label|ERROR=${failure.javaClass.name}:${failure.message?.take(160)}" +
                        "|root=${root.javaClass.name}:${root.message?.replace('\n', ' ')?.take(160)}",
                )
            }
        }

        attempt("explicit_json_from_java_map") {
            executor.evaluate(
                """
                %dw 2.0
                output application/json
                ---
                payload
                """.trimIndent(),
                DataWeaveContext.from(javaEvent, Properties()),
            )
        }
        attempt("explicit_json_from_json_text") {
            executor.evaluate(
                """
                %dw 2.0
                output application/json
                ---
                payload
                """.trimIndent(),
                DataWeaveContext.from(jsonEvent, Properties()),
            )
        }
        attempt("write_json_from_java_map") {
            executor.evaluate(
                """write(payload, "application/json")""",
                DataWeaveContext.from(javaEvent, Properties()),
            )
        }
        attempt("identity_java_map") {
            executor.evaluate("payload", DataWeaveContext.from(javaEvent, Properties()))
        }
        attempt("identity_json_text") {
            executor.evaluate("payload", DataWeaveContext.from(jsonEvent, Properties()))
        }
        attempt("expression_payload_json_text") {
            evaluator.evaluate("#[payload]", jsonEvent)
        }
        attempt("expression_payload_json_map") {
            evaluator.evaluate("#[payload]", mapJsonEvent)
        }
        report("original_json_text", jsonEvent.payload as TypedValue<Any>)
    }

}

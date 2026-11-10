package dev.museframework.engine.integration

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.EvaluationRequest
import dev.museframework.engine.api.streaming.NonRepeatableCursorStreamProvider
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mule.weave.v2.core.io.SeekableStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Properties

class JsonPayloadIT {

    private val executor = MuseDataWeaveExecutor()
    private val evaluator = MuseExpressionEvaluatorImpl(executor, Properties())
    private val objectMapper = ObjectMapper()

    private val jsonOutputScript =
        """
        %dw 2.0
        output application/json
        ---
        payload
        """.trimIndent()

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

    @Test
    fun `explicit json output writes parseable bytes from a java map`() {
        val context =
            DataWeaveContext.from(
                MuseEvent(payload = TypedValue.of(linkedMapOf("message" to "Resource not found"), TypedValue.MIME_JAVA)),
                Properties(),
            )

        val result = executor.evaluate(jsonOutputScript, context)
        val content = result?.getOrNull()

        assertEquals(TypedValue.MIME_JSON, result?.mimeType)
        assertEquals(Charsets.UTF_8, result?.charset)
        assertTrue(content is InputStream, "Expected an InputStream writer representation, got ${content?.javaClass?.name}")
        val bytes = (content as InputStream).use { it.readBytes() }
        val parsed = objectMapper.readValue(bytes, object : TypeReference<Map<String, Any?>>() {})
        assertEquals(mapOf("message" to "Resource not found"), parsed)
    }

    @Test
    fun `explicit json output writes the same bytes from json text`() {
        val mapContext =
            DataWeaveContext.from(
                MuseEvent(payload = TypedValue.of(linkedMapOf("message" to "Resource not found"), TypedValue.MIME_JAVA)),
                Properties(),
            )
        val textContext =
            DataWeaveContext.from(
                MuseEvent(payload = TypedValue.of("""{"message":"Resource not found"}""", TypedValue.MIME_JSON)),
                Properties(),
            )

        val mapBytes = (executor.evaluate(jsonOutputScript, mapContext)?.getOrNull() as InputStream).use { it.readBytes() }
        val textBytes = (executor.evaluate(jsonOutputScript, textContext)?.getOrNull() as InputStream).use { it.readBytes() }

        assertArrayEquals(mapBytes, textBytes)
        assertEquals(
            mapOf("message" to "Resource not found"),
            objectMapper.readValue(textBytes, object : TypeReference<Map<String, Any?>>() {}),
        )
    }

    @Test
    fun `dataweave json stream repeatability and close behavior are measured`() {
        val context =
            DataWeaveContext.from(
                MuseEvent(payload = TypedValue.of(linkedMapOf("message" to "Resource not found"), TypedValue.MIME_JAVA)),
                Properties(),
            )
        val content = executor.evaluate(jsonOutputScript, context)?.getOrNull()
        val stream = content as? SeekableStream ?: error("Expected a DataWeave SeekableStream, got ${content?.javaClass?.name}")

        assertTrue(stream.canBeConsumedMultipleTimes(), "DataWeave in-memory writer output should be repeatable")
        assertEquals(false, stream.requireClose(), "DataWeave in-memory writer output should not require closing")
        val first = (content as InputStream).readBytes()
        stream.seek(0)
        val second = (content as InputStream).readBytes()
        assertArrayEquals(first, second)
        (content as InputStream).close()
        assertTrue(stream.isClosed, "Closing the DataWeave writer stream should mark it closed")
    }

    @Test
    fun `ordinary standalone payload evaluation still parses JSON for existing consumers`() {
        val input = TypedValue.of<Any>("""[{"id": 1}]""", TypedValue.MIME_JSON)
        val result = evaluator.evaluate("#[payload]", MuseEvent(payload = input))
        val list = result?.value as? List<*> ?: error("Expected a DataWeave parsed collection")
        val row = list.single() as? Map<*, *> ?: error("Expected a DataWeave parsed row")

        assertEquals(1, (row["id"] as Number).toInt())
        assertEquals(TypedValue.MIME_JAVA, result.mimeType)
    }

    @Test
    fun `standalone payload expression preserves serialized json text and metadata`() {
        val originalBytes = """{"message":"Risposta non trovata"}""".toByteArray(Charsets.UTF_8)
        val input = TypedValue.of<Any>(String(originalBytes, Charsets.UTF_8), TypedValue.MIME_JSON, Charsets.UTF_8)

        val result = evaluator.evaluate(EvaluationRequest.PayloadPreservingValue("#[payload]"), MuseEvent(payload = input))

        assertSame(input.value, result?.value, "The original serialized JSON text must be preserved")
        assertEquals(TypedValue.MIME_JSON, result?.mimeType)
        assertEquals(Charsets.UTF_8, result?.charset)
        assertArrayEquals(originalBytes, (result?.value as String).toByteArray(result.charset))
    }

    @Test
    fun `standalone payload expression preserves binary payload and charset`() {
        val originalBytes = byteArrayOf(0, 1, 2, 127, -1)
        val charset = Charset.forName("ISO-8859-1")
        val input = TypedValue.of<Any>(originalBytes, TypedValue.MIME_OCTET_STREAM, charset)

        val result = evaluator.evaluate(EvaluationRequest.PayloadPreservingValue("#[payload]"), MuseEvent(payload = input))

        assertSame(input.value, result?.value)
        assertEquals(TypedValue.MIME_OCTET_STREAM, result?.mimeType)
        assertEquals(charset, result?.charset)
        assertArrayEquals(originalBytes, result?.value as ByteArray)
    }

    @Test
    fun `standalone payload expression preserves streaming provider without consuming it`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("stream-payload".toByteArray())
        val input = TypedValue.of<Any>(provider, TypedValue.MIME_TEXT)

        val result = evaluator.evaluate(EvaluationRequest.PayloadPreservingValue("#[payload]"), MuseEvent(payload = input))

        assertSame(provider, result?.value)
        assertEquals(TypedValue.MIME_TEXT, result?.mimeType)
        assertEquals("stream-payload", provider.openCursor().use { it.readBytes().decodeToString() })
    }

    @Test
    fun `standalone payload expression does not claim a non repeatable cursor`() {
        val source = ByteArrayInputStream("one-shot".toByteArray())
        val provider = NonRepeatableCursorStreamProvider(source)
        val input = TypedValue.of<Any>(provider, TypedValue.MIME_OCTET_STREAM)

        try {
            val result = evaluator.evaluate(EvaluationRequest.PayloadPreservingValue("#[payload]"), MuseEvent(payload = input))

            assertSame(provider, result?.value)
            assertEquals("one-shot", provider.openCursor().use { it.readBytes().decodeToString() })
        } finally {
            provider.releaseResources()
        }
    }

    @Test
    fun `standalone payload expression with surrounding whitespace preserves identity`() {
        val originalBytes = """{"message":"kept"}""".toByteArray(Charsets.UTF_8)
        val input = TypedValue.of<Any>(String(originalBytes, Charsets.UTF_8), TypedValue.MIME_JSON, Charsets.UTF_8)

        val result = evaluator.evaluate(EvaluationRequest.PayloadPreservingValue("  #[payload]  "), MuseEvent(payload = input))

        assertSame(input.value, result?.value)
        assertEquals(TypedValue.MIME_JSON, result?.mimeType)
    }

    @Test
    fun `non identity payload expressions still evaluate through dataweave`() {
        val input = TypedValue.of<Any>("""{"message":"Resource not found"}""", TypedValue.MIME_JSON)

        val result = evaluator.evaluate("#[payload.message]", MuseEvent(payload = input))

        assertEquals("Resource not found", result?.value)
        assertEquals(TypedValue.MIME_JAVA, result?.mimeType)
    }
}

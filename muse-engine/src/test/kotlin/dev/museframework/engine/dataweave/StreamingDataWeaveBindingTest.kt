package dev.museframework.engine.dataweave

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorIteratorProvider
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.api.streaming.NonRepeatableCursorStreamProvider
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingDataWeaveBindingTest {

    @Test
    fun `byte stream payload binding opens fresh cursor`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val event = MuseEvent(payload = TypedValue.of(provider as Any, TypedValue.MIME_OCTET_STREAM))

        val first = PayloadBindingValueFactory.payloadValue(event)
        val second = PayloadBindingValueFactory.payloadValue(event)

        val firstBytes = (first as java.io.InputStream).readBytes()
        val secondBytes = (second as java.io.InputStream).readBytes()
        assertContentEquals("abc".toByteArray(), firstBytes)
        assertContentEquals("abc".toByteArray(), secondBytes)
    }

    @Test
    fun `object stream payload binding opens fresh iterator`() {
        val provider = RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf("a", "b"))
        val event = MuseEvent(payload = TypedValue.of(provider as Any))

        val first = PayloadBindingValueFactory.payloadValue(event)
        val second = PayloadBindingValueFactory.payloadValue(event)

        assertEquals(listOf("a", "b"), (first as Iterator<*>).asSequence().toList())
        assertEquals(listOf("a", "b"), (second as Iterator<*>).asSequence().toList())
    }

    @Test
    fun `non stream payload binding returns raw value`() {
        val event = MuseEvent(payload = TypedValue.of("hello"))

        val value = PayloadBindingValueFactory.payloadValue(event)

        assertTrue(value is String)
        assertEquals("hello", value)
    }

    @Test
    fun `creating a binding does not claim a non repeatable cursor`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        val event = MuseEvent(payload = TypedValue.of(provider as Any, TypedValue.MIME_OCTET_STREAM))

        val binding = PayloadBindingValueFactory.payloadValue(event)
        val cursor = provider.openCursor()

        assertTrue(binding is java.io.InputStream)
        assertEquals("abc", cursor.readBytes().decodeToString())
    }

    @Test
    fun `expression that only reads variables leaves non repeatable payload available`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        val event =
            MuseEvent(
                payload = TypedValue.of(provider as Any, TypedValue.MIME_OCTET_STREAM),
                variables = mapOf("answer" to TypedValue.of("ok" as Any)),
            )

        val result = MuseDataWeaveExecutor().evaluate("vars.answer", DataWeaveContext(event))

        assertEquals("ok", result?.getOrNull())
        assertEquals("abc", provider.openCursor().readBytes().decodeToString())
    }

    @Test
    fun `stream providers in variables use lazy cursor bindings`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("variable-stream".toByteArray())
        val value = PayloadBindingValueFactory.typedValue(TypedValue.of(provider as Any))

        assertTrue(value is java.io.InputStream)
        assertEquals("variable-stream", (value as java.io.InputStream).readBytes().decodeToString())
    }
}

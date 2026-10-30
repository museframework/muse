package dev.museframework.engine.api.event

import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorIteratorProvider
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypedValueStreamingTest {

    @Test
    fun `recognizes cursor stream providers`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val typed = TypedValue.of(provider as Any, TypedValue.MIME_OCTET_STREAM)

        assertTrue(typed.isStreamProvider())
        assertNotNull(typed.asCursorStreamProvider())
        assertNull(typed.asCursorIteratorProvider<Any>())
    }

    @Test
    fun `recognizes cursor iterator providers`() {
        val provider = RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf("a", "b"))
        val typed = TypedValue.of(provider as Any)

        assertTrue(typed.isStreamProvider())
        assertNotNull(typed.asCursorIteratorProvider<String>())
        assertNull(typed.asCursorStreamProvider())
    }
}

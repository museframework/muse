package dev.museframework.engine.api.streaming

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingEventResourcesTest {
    @Test
    fun `registration discovers providers added to previously visited mutable containers`() {
        val list = mutableListOf<Any>()
        val map = mutableMapOf<String, Any>()
        val array = arrayOfNulls<Any>(1)
        val event = MuseEvent(payload = TypedValue.of(listOf(list, map, array)))
        val registry = StreamingResourceRegistry()
        StreamingEventResources.registerEventProviders(event, registry)

        val providers = List(3) { GenericProvider() }
        list += providers[0]
        map["late"] = providers[1]
        array[0] = providers[2]
        StreamingEventResources.registerEventProviders(event, registry)

        providers.forEach { assertTrue(it.isManaged) }
        registry.releaseAll()
        providers.forEach { assertEquals(1, it.closeCount); assertEquals(1, it.releaseCount) }
    }


    @Test
    fun `discovers providers across nested event aggregates and every supported container`() {
        val providers = List(5) { GenericProvider() }
        val nested = MuseEvent(
            payload = TypedValue.of(providers[0]),
            attributes = TypedValue.of(listOf(TypedValue.of(providers[1]))),
            variables = mapOf("nested" to TypedValue.of(arrayOf(providers[2]))),
        )
        val event = MuseEvent(
            payload = TypedValue.of(listOf(nested)),
            attributes = TypedValue.of(mapOf(providers[3] to "key")),
            variables = mapOf("value" to TypedValue.of(mapOf("provider" to providers[4]))),
        )
        val registry = StreamingResourceRegistry()

        StreamingEventResources.registerEventProviders(event, registry)
        providers.forEach { assertTrue(it.isManaged) }
        registry.releaseAll()

        providers.forEach {
            assertEquals(1, it.closeCount)
            assertEquals(1, it.releaseCount)
        }
    }

    @Test
    fun `aliases cycles and equal distinct providers use identity without consuming lazy values`() {
        val first = GenericProvider()
        val second = GenericProvider()
        assertEquals(first, second)
        val list = mutableListOf<Any?>()
        val array = arrayOfNulls<Any>(2)
        val map = mutableMapOf<String, Any?>()
        val lazyIterable = Iterable<Any> { error("Iterable must not be enumerated") }
        val lazySequence = Sequence<Any> { error("Sequence must not be enumerated") }
        val iterator = object : Iterator<Any> {
            override fun hasNext(): Boolean = error("Iterator must not be touched")
            override fun next(): Any = error("Iterator must not be touched")
        }
        val hidden = GenericProvider()
        val opaque = object { val provider = hidden }
        val event = MuseEvent(payload = TypedValue.of(list))
        list.addAll(listOf(first, first, second, array, map, lazyIterable, lazySequence, iterator, opaque))
        array[0] = list
        array[1] = event
        map["self"] = map
        map["alias"] = first
        val registry = StreamingResourceRegistry()

        StreamingEventResources.registerEventProviders(event, registry)
        registry.releaseAll()

        assertEquals(1, first.closeCount)
        assertEquals(1, first.releaseCount)
        assertEquals(1, second.closeCount)
        assertEquals(1, second.releaseCount)
        assertFalse(hidden.isManaged)
        assertEquals(0, hidden.releaseCount)
    }

    private class GenericProvider : MuseCursorProvider<MuseCursor> {
        var closeCount = 0
        var releaseCount = 0
        override val isClosed: Boolean
            get() = closeCount > 0
        override fun openCursor(): MuseCursor = error("Discovery must never open a provider")
        override fun close() {
            closeCount++
        }
        override fun releaseResources() {
            releaseCount++
        }
        override fun equals(other: Any?): Boolean = other is GenericProvider
        override fun hashCode(): Int = 0
    }

    @Test
    fun `registers payload stream provider with registry`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val cursor = provider.openCursor()
        val event = MuseEvent(payload = TypedValue.of(provider as Any, TypedValue.MIME_OCTET_STREAM))
        val registry = StreamingResourceRegistry()

        StreamingEventResources.registerEventProviders(event, registry)
        registry.releaseAll()

        assertTrue(cursor.isReleased)
    }

    @Test
    fun `registers non repeatable stream provider with registry`() {
        val provider = NonRepeatableCursorStreamProvider(java.io.ByteArrayInputStream("abc".toByteArray()))
        val cursor = provider.openCursor()
        val event = MuseEvent(payload = TypedValue.of(provider as Any, TypedValue.MIME_OCTET_STREAM))
        val registry = StreamingResourceRegistry()

        StreamingEventResources.registerEventProviders(event, registry)
        registry.releaseAll()

        assertTrue(cursor.isReleased)
    }
}

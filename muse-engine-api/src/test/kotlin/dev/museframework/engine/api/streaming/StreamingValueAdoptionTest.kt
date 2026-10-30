package dev.museframework.engine.api.streaming

import dev.museframework.engine.api.event.TypedValue
import org.junit.jupiter.api.Test
import kotlin.test.*

class StreamingValueAdoptionTest {
    @Test
    fun `value adoption discovers nested providers once without traversing lazy iterables`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes(byteArrayOf(1))
        val values = linkedMapOf<Any, Any?>()
        values["self"] = values
        values[provider] = arrayOf(listOf(provider, TypedValue.of(provider)))
        values["lazy"] = Iterable<Any> { error("lazy iteration must not be requested") }
        val registry = StreamingResourceRegistry()
        try {
            assertEquals(listOf(provider), StreamingEventResources.valueProviders(TypedValue.of(values)))
            StreamingEventResources.registerValueProviders(values, registry)
            assertTrue(provider.isManaged)
        } finally {
            registry.releaseAll()
        }
        assertTrue(provider.isClosed)
    }
}

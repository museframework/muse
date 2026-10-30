package dev.museframework.engine.api.streaming

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingProvidersTest {

    @Test
    fun `creates in memory byte provider from repeatable in memory strategy`() {
        val provider = StreamingProviders.byteProvider(
            inputStream = ByteArrayInputStream("abc".toByteArray()),
            strategy = StreamingStrategy.RepeatableInMemory(maxBufferSizeBytes = 10),
        )

        provider.use {
            repeat(2) { assertEquals("abc", provider.openCursor().use { it.readBytes().decodeToString() }) }
        }
    }

    @Test
    fun `creates in memory object provider from repeatable in memory strategy`() {
        val provider = StreamingProviders.objectProvider(
            iterable = listOf("a", "b"),
            strategy = StreamingStrategy.RepeatableInMemory(maxObjectBufferSize = 2),
        )

        provider.use {
            repeat(2) { assertEquals(listOf("a", "b"), provider.openCursor().use { it.asSequence().toList() }) }
        }
    }

    @Test
    fun `creates file store byte provider from repeatable file store strategy`() {
        val provider = StreamingProviders.byteProvider(
            inputStream = ByteArrayInputStream("abcdef".toByteArray()),
            strategy = StreamingStrategy.RepeatableFileStore(inMemorySizeBytes = 3),
        )

        provider.use {
            repeat(2) { assertEquals("abcdef", provider.openCursor().use { it.readBytes().decodeToString() }) }
        }
    }

    @Test
    fun `creates non repeatable byte provider from non repeatable strategy`() {
        val provider = StreamingProviders.byteProvider(
            inputStream = ByteArrayInputStream("abc".toByteArray()),
            strategy = StreamingStrategy.NonRepeatable,
        )

        provider.use {
            assertEquals("abc", provider.openCursor().use { it.readBytes().decodeToString() })
            assertFailsWith<IllegalStateException> { provider.openCursor() }
        }
    }

    @Test
    fun `creates non repeatable object provider from non repeatable strategy`() {
        val provider = StreamingProviders.objectProvider(
            iterable = listOf("x", "y"),
            strategy = StreamingStrategy.NonRepeatable,
        )

        provider.use {
            assertEquals(listOf("x", "y"), provider.openCursor().use { it.asSequence().toList() })
            assertFailsWith<IllegalStateException> { provider.openCursor() }
        }
    }
}

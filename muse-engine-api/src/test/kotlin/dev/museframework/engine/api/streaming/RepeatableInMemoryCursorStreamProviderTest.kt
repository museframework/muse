package dev.museframework.engine.api.streaming

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RepeatableInMemoryCursorStreamProviderTest {

    @Test
    fun `opens independent cursors from the beginning`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abcdef".toByteArray())

        val first = provider.openCursor()
        val second = provider.openCursor()

        assertEquals('a'.code, first.read())
        assertEquals('b'.code, first.read())
        assertEquals('a'.code, second.read())
        assertEquals(2L, first.position)
        assertEquals(1L, second.position)
    }

    @Test
    fun `cursor can seek and read again`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abcdef".toByteArray())
        val cursor = provider.openCursor()
        val buffer = ByteArray(3)

        assertEquals(3, cursor.read(buffer))
        assertContentEquals("abc".toByteArray(), buffer)

        cursor.seek(1)
        assertEquals('b'.code, cursor.read())
        assertEquals(2L, cursor.position)
    }

    @Test
    fun `closed provider rejects new cursors while existing cursors still read`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val cursor = provider.openCursor()

        provider.close()

        assertTrue(provider.isClosed)
        assertEquals('a'.code, cursor.read())
        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }

    @Test
    fun `released cursor refuses reads`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val cursor = provider.openCursor()

        cursor.release()

        assertTrue(cursor.isReleased)
        assertFailsWith<StreamingResourceReleasedException> { cursor.read() }
    }

    @Test
    fun `provider enforces maximum size while materializing input stream`() {
        val source = ByteArrayInputStream("abcdef".toByteArray())

        assertFailsWith<StreamMaximumSizeExceededException> {
            RepeatableInMemoryCursorStreamProvider.fromInputStream(source, maxBufferSizeBytes = 5)
        }
    }

    @Test
    fun `provider grows by configured increments up to the maximum`() {
        val provider =
            RepeatableInMemoryCursorStreamProvider.fromInputStream(
                inputStream = ByteArrayInputStream("abcde".toByteArray()),
                initialBufferSizeBytes = 1,
                bufferSizeIncrementBytes = 2,
                maxBufferSizeBytes = 5,
            )

        assertEquals("abcde", provider.openCursor().readBytes().decodeToString())
        assertFailsWith<StreamMaximumSizeExceededException> {
            RepeatableInMemoryCursorStreamProvider.fromInputStream(
                inputStream = ByteArrayInputStream("abcdef".toByteArray()),
                initialBufferSizeBytes = 1,
                bufferSizeIncrementBytes = 2,
                maxBufferSizeBytes = 5,
            )
        }
    }

    @Test
    fun `provider rejects a zero buffer increment`() {
        assertFailsWith<IllegalArgumentException> {
            RepeatableInMemoryCursorStreamProvider.fromInputStream(
                inputStream = ByteArrayInputStream("abc".toByteArray()),
                bufferSizeIncrementBytes = 0,
            )
        }
    }

    @Test
    fun `provider releases all resources idempotently`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val cursor = provider.openCursor()

        provider.releaseResources()
        provider.releaseResources()

        assertTrue(cursor.isReleased)
        assertFailsWith<StreamingResourceReleasedException> { cursor.read() }
        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }
}

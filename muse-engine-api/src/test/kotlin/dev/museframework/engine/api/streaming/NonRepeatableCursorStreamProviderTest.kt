package dev.museframework.engine.api.streaming

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NonRepeatableCursorStreamProviderTest {

    @Test
    fun `openCursor returns a readable stream`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("hello".toByteArray()))
        val cursor = provider.openCursor()

        val bytes = cursor.readBytes()
        assertContentEquals("hello".toByteArray(), bytes)
    }

    @Test
    fun `second openCursor throws IllegalStateException`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        provider.openCursor()

        assertFailsWith<IllegalStateException> { provider.openCursor() }
    }

    @Test
    fun `size returns minus one`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        assertEquals(-1L, provider.size)
    }

    @Test
    fun `releaseResources closes input stream and releases cursors`() {
        var closed = false
        val inputStream = object : InputStream() {
            override fun read(): Int = -1
            override fun close() { closed = true }
        }
        val provider = NonRepeatableCursorStreamProvider(inputStream)
        val cursor = provider.openCursor()

        provider.releaseResources()

        assertTrue(closed)
        assertTrue(cursor.isReleased)
        assertTrue(provider.isClosed)
    }

    @Test
    fun `releaseResources is idempotent`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        val cursor = provider.openCursor()

        provider.releaseResources()
        provider.releaseResources()

        assertTrue(cursor.isReleased)
        assertFailsWith<StreamingResourceReleasedException> { cursor.read() }
        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }

    @Test
    fun `openCursor after close throws StreamingProviderClosedException`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        provider.close()

        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }

    @Test
    fun `openCursor after releaseResources throws StreamingProviderClosedException`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        provider.releaseResources()

        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }

    @Test
    fun `read after cursor close throws StreamingException`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        val cursor = provider.openCursor()
        cursor.close()

        assertFailsWith<StreamingException> { cursor.read() }
    }

    @Test
    fun `read after cursor release throws StreamingResourceReleasedException`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        val cursor = provider.openCursor()
        cursor.release()

        assertFailsWith<StreamingResourceReleasedException> { cursor.read() }
    }

    @Test
    fun `seek forward advances position`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abcdef".toByteArray()))
        val cursor = provider.openCursor()

        cursor.seek(3)
        assertEquals(3L, cursor.position)
        assertEquals('d'.code, cursor.read())
    }

    @Test
    fun `seek forward handles partial skip results`() {
        val source =
            object : ByteArrayInputStream("abcdef".toByteArray()) {
                override fun skip(amount: Long): Long = super.skip(minOf(amount, 1L))
            }
        val cursor = NonRepeatableCursorStreamProvider(source).openCursor()

        cursor.seek(4)

        assertEquals(4L, cursor.position)
        assertEquals('e'.code, cursor.read())
    }

    @Test
    fun `seek forward falls back to reading when skip makes no progress`() {
        val source =
            object : ByteArrayInputStream("abcdef".toByteArray()) {
                override fun skip(amount: Long): Long = 0L
            }
        val cursor = NonRepeatableCursorStreamProvider(source).openCursor()

        cursor.seek(3)

        assertEquals(3L, cursor.position)
        assertEquals('d'.code, cursor.read())
    }

    @Test
    fun `seek beyond eof reports the actual consumed position`() {
        val source =
            object : ByteArrayInputStream("abc".toByteArray()) {
                override fun skip(amount: Long): Long = 0L
            }
        val cursor = NonRepeatableCursorStreamProvider(source).openCursor()

        cursor.seek(10)

        assertEquals(3L, cursor.position)
        assertEquals(-1, cursor.read())
    }

    @Test
    fun `seek backward throws IllegalArgumentException`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abcdef".toByteArray()))
        val cursor = provider.openCursor()
        cursor.read() // position = 1

        assertFailsWith<IllegalArgumentException> { cursor.seek(0) }
    }

    @Test
    fun `seek to current position is no-op`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abcdef".toByteArray()))
        val cursor = provider.openCursor()
        cursor.read() // position = 1

        cursor.seek(1)
        assertEquals(1L, cursor.position)
    }

    @Test
    fun `empty input stream returns eof on read`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream(ByteArray(0)))
        val cursor = provider.openCursor()

        assertEquals(-1, cursor.read())
    }

    @Test
    fun `read with invalid buffer offset throws IndexOutOfBoundsException`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        val cursor = provider.openCursor()
        val buffer = ByteArray(3)

        assertFailsWith<IndexOutOfBoundsException> { cursor.read(buffer, -1, 1) }
        assertFailsWith<IndexOutOfBoundsException> { cursor.read(buffer, 0, -1) }
        assertFailsWith<IndexOutOfBoundsException> { cursor.read(buffer, 0, 4) }
    }

    @Test
    fun `read with buffer delegates to input stream`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abcdef".toByteArray()))
        val cursor = provider.openCursor()
        val buffer = ByteArray(3)

        val count = cursor.read(buffer, 0, 3)
        assertEquals(3, count)
        assertContentEquals("abc".toByteArray(), buffer)
        assertEquals(3L, cursor.position)
    }

    @Test
    fun `available delegates to input stream`() {
        val provider = NonRepeatableCursorStreamProvider(ByteArrayInputStream("abc".toByteArray()))
        val cursor = provider.openCursor()

        assertEquals(3, cursor.available())
    }
}

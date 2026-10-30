package dev.museframework.engine.api.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NonRepeatableCursorIteratorProviderTest {

    @Test
    fun `openCursor returns a readable iterator`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a", "b", "c").iterator())
        val cursor = provider.openCursor()

        assertEquals(listOf("a", "b", "c"), cursor.asSequence().toList())
    }

    @Test
    fun `second openCursor throws IllegalStateException`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a", "b").iterator())
        provider.openCursor()

        assertFailsWith<IllegalStateException> { provider.openCursor() }
    }

    @Test
    fun `size returns minus one`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        assertEquals(-1L, provider.size)
    }

    @Test
    fun `releaseResources releases cursors and marks provider closed`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a", "b").iterator())
        val cursor = provider.openCursor()

        provider.releaseResources()

        assertTrue(cursor.isReleased)
        assertTrue(provider.isClosed)
    }

    @Test
    fun `releaseResources is idempotent`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        val cursor = provider.openCursor()

        provider.releaseResources()
        provider.releaseResources()

        assertTrue(cursor.isReleased)
        assertFailsWith<StreamingResourceReleasedException> { cursor.hasNext() }
        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }

    @Test
    fun `openCursor after close throws StreamingProviderClosedException`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        provider.close()

        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }

    @Test
    fun `openCursor after releaseResources throws StreamingProviderClosedException`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        provider.releaseResources()

        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }

    @Test
    fun `hasNext after cursor close throws StreamingException`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        val cursor = provider.openCursor()
        cursor.close()

        assertFailsWith<StreamingException> { cursor.hasNext() }
    }

    @Test
    fun `hasNext after cursor release throws StreamingResourceReleasedException`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        val cursor = provider.openCursor()
        cursor.release()

        assertFailsWith<StreamingResourceReleasedException> { cursor.hasNext() }
    }

    @Test
    fun `seek forward advances position`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a", "b", "c", "d").iterator())
        val cursor = provider.openCursor()

        cursor.seek(2)
        assertEquals(2L, cursor.position)
        assertEquals("c", cursor.next())
    }

    @Test
    fun `seek backward throws IllegalArgumentException`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a", "b").iterator())
        val cursor = provider.openCursor()
        cursor.next() // position = 1

        assertFailsWith<IllegalArgumentException> { cursor.seek(0) }
    }

    @Test
    fun `exhausted iterator hasNext returns false`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        val cursor = provider.openCursor()
        cursor.next()

        assertFalse(cursor.hasNext())
    }

    @Test
    fun `next after cursor close throws StreamingException`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        val cursor = provider.openCursor()
        cursor.close()

        assertFailsWith<StreamingException> { cursor.next() }
    }

    @Test
    fun `next after cursor release throws StreamingResourceReleasedException`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        val cursor = provider.openCursor()
        cursor.release()

        assertFailsWith<StreamingResourceReleasedException> { cursor.next() }
    }

    @Test
    fun `next on exhausted iterator throws NoSuchElementException`() {
        val provider = NonRepeatableCursorIteratorProvider(listOf("a").iterator())
        val cursor = provider.openCursor()
        cursor.next()

        assertFailsWith<NoSuchElementException> { cursor.next() }
    }
}

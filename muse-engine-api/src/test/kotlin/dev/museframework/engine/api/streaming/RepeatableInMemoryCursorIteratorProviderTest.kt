package dev.museframework.engine.api.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepeatableInMemoryCursorIteratorProviderTest {

    @Test
    fun `opens independent iterators from the beginning`() {
        val provider = RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf("a", "b", "c"))

        val first = provider.openCursor()
        val second = provider.openCursor()

        assertEquals("a", first.next())
        assertEquals("b", first.next())
        assertEquals("a", second.next())
        assertEquals(2L, first.position)
        assertEquals(1L, second.position)
    }

    @Test
    fun `iterator can seek and read again`() {
        val provider = RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf(10, 20, 30))
        val cursor = provider.openCursor()

        assertEquals(10, cursor.next())
        assertEquals(20, cursor.next())

        cursor.seek(0)

        assertEquals(10, cursor.next())
        assertEquals(1L, cursor.position)
    }

    @Test
    fun `iterator exposes size`() {
        val provider = RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf(1, 2, 3))

        assertEquals(3L, provider.size)
        assertEquals(3L, provider.openCursor().size)
    }

    @Test
    fun `provider enforces maximum object count`() {
        assertFailsWith<StreamMaximumSizeExceededException> {
            RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf(1, 2, 3), maxObjectBufferSize = 2)
        }
    }

    @Test
    fun `provider applies configured object buffer increments`() {
        val provider =
            RepeatableInMemoryCursorIteratorProvider.fromIterable(
                iterable = listOf(1, 2, 3, 4, 5),
                initialObjectBufferSize = 1,
                objectBufferSizeIncrement = 2,
                maxObjectBufferSize = 5,
            )

        assertEquals(listOf(1, 2, 3, 4, 5), provider.openCursor().asSequence().toList())
    }

    @Test
    fun `provider rejects a zero object buffer increment`() {
        assertFailsWith<IllegalArgumentException> {
            RepeatableInMemoryCursorIteratorProvider.fromIterable(
                iterable = listOf(1),
                objectBufferSizeIncrement = 0,
            )
        }
    }

    @Test
    fun `closed provider rejects new iterators while existing iterator still reads`() {
        val provider = RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf("a", "b"))
        val cursor = provider.openCursor()

        provider.close()

        assertTrue(provider.isClosed)
        assertEquals("a", cursor.next())
        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }

    @Test
    fun `released cursor refuses iteration`() {
        val provider = RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf("a"))
        val cursor = provider.openCursor()

        cursor.release()

        assertTrue(cursor.isReleased)
        assertFailsWith<StreamingResourceReleasedException> { cursor.hasNext() }
    }

    @Test
    fun `provider releases all iterators idempotently`() {
        val provider = RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf("a", "b"))
        val cursor = provider.openCursor()

        provider.releaseResources()
        provider.releaseResources()

        assertTrue(cursor.isReleased)
        assertFailsWith<StreamingProviderClosedException> { provider.openCursor() }
    }
}

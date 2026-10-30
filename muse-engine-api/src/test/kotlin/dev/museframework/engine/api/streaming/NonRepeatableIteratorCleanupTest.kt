package dev.museframework.engine.api.streaming

import org.junit.jupiter.api.Test
import kotlin.test.*

class NonRepeatableIteratorCleanupTest {
    @Test
    fun `releasing an unopened provider closes its delegate once`() {
        val delegate = CloseableIterator()
        val provider = NonRepeatableCursorIteratorProvider(delegate)
        provider.close()
        assertEquals(0, delegate.closes)
        provider.releaseResources()
        provider.releaseResources()
        assertEquals(1, delegate.closes)
    }

    @Test
    fun `closing a cursor leaves delegate cleanup with the owning lease`() {
        val delegate = CloseableIterator()
        val provider = NonRepeatableCursorIteratorProvider(delegate)
        val registry = StreamingResourceRegistry()
        registry.register(provider)
        val lease = registry.retain(listOf(provider))
        provider.openCursor().use { assertEquals(1, it.next()) }
        assertEquals(0, delegate.closes)
        registry.releaseAll()
        assertEquals(0, delegate.closes)
        lease.close()
        lease.close()
        assertEquals(1, delegate.closes)
        assertTrue(provider.isClosed)
        assertFalse(provider.isManaged)
    }

    @Test
    fun `failed delegate cleanup is reported once and registry ownership is finalized`() {
        val failure = IllegalStateException("reader close failed")
        val delegate = CloseableIterator(failure)
        val provider = NonRepeatableCursorIteratorProvider(delegate)
        val lease = StreamingResourceRegistry.ownedClaim(provider)
        val error = assertFailsWith<StreamingCleanupException> { lease.close() }
        assertSame(failure, error.failures.single())
        lease.close()
        assertEquals(1, delegate.closes)
        assertFalse(provider.isManaged)
    }

    private class CloseableIterator(val failure: Throwable? = null) : Iterator<Int>, AutoCloseable {
        var closes = 0
        override fun hasNext() = true
        override fun next() = 1
        override fun close() { closes++; failure?.let { throw it } }
    }
}

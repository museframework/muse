package dev.museframework.engine.api.streaming

import org.junit.jupiter.api.Test
import kotlin.test.*

class StreamingDiagnosticsTest {
    @Test
    fun `statistics distinguish provider identities from independently retained claims`() {
        val baseline = StreamingResourceRegistry.statistics()
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes(byteArrayOf(1))
        val registry = StreamingResourceRegistry()
        registry.register(provider)
        registry.register(provider)
        val lease = registry.retain(listOf(provider))
        try {
            val retained = StreamingResourceRegistry.statistics()
            assertEquals(baseline.activeProviderIdentities + 1, retained.activeProviderIdentities)
            assertEquals(baseline.registryClaims + 2, retained.registryClaims)
            registry.releaseAll()
            val shared = StreamingResourceRegistry.statistics()
            assertEquals(baseline.activeProviderIdentities + 1, shared.activeProviderIdentities)
            assertEquals(baseline.registryClaims + 1, shared.registryClaims)
        } finally {
            registry.releaseAll()
            lease.close()
        }
        assertEquals(baseline, StreamingResourceRegistry.statistics())
    }

    @Test
    fun `statistics expose cleanup in progress and recover after cleanup failure`() {
        val baseline = StreamingResourceRegistry.statistics()
        val failure = IllegalStateException("cleanup")
        val iterator = object : Iterator<Int>, AutoCloseable {
            override fun hasNext() = false
            override fun next(): Int = throw NoSuchElementException()
            override fun close() {
                val during = StreamingResourceRegistry.statistics()
                assertEquals(baseline.activeProviderIdentities, during.activeProviderIdentities)
                assertEquals(baseline.registryClaims, during.registryClaims)
                assertEquals(baseline.cleanupInProgress + 1, during.cleanupInProgress)
                throw failure
            }
        }
        val lease = StreamingResourceRegistry.ownedClaim(NonRepeatableCursorIteratorProvider(iterator))
        val error = assertFailsWith<StreamingCleanupException> { lease.close() }
        assertSame(failure, error.failures.single())
        assertEquals(baseline, StreamingResourceRegistry.statistics())
    }
}

package dev.museframework.engine.api.streaming

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingResourceRegistryTest {

    @Test
    fun `retained subset does not extend unrelated provider lifetime`() {
        val root = StreamingResourceRegistry()
        val exported = root.register(RecordingProvider())
        val local = root.register(RecordingProvider())
        val response = root.retain(listOf(exported))

        root.releaseAll()

        assertEquals(1, local.releaseCount.get())
        assertEquals(0, exported.releaseCount.get())
        response.close()
        assertEquals(1, exported.releaseCount.get())
    }

    @Test
    fun `retain requires source membership even when another registry owns the provider`() {
        val source = StreamingResourceRegistry()
        val other = StreamingResourceRegistry()
        val provider = other.register(RecordingProvider())

        assertFailsWith<IllegalArgumentException> { source.retain(listOf(provider)) }
        source.releaseAll()
        assertEquals(0, provider.releaseCount.get())
        other.releaseAll()
        assertEquals(1, provider.releaseCount.get())
    }

    @Test
    fun `transferred provider cannot subsequently be retained by its former registry`() {
        val registry = StreamingResourceRegistry()
        val provider = registry.register(RecordingProvider())
        assertTrue(registry.transfer(provider))

        assertFailsWith<IllegalArgumentException> { registry.retain(listOf(provider)) }
        registry.releaseAll()
        assertEquals(0, provider.releaseCount.get())
        provider.close()
        provider.releaseResources()
    }

    @Test
    fun `release closes providers and releases resources`() {
        val registry = StreamingResourceRegistry()
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val cursor = provider.openCursor()

        assertFalse(provider.isManaged)
        registry.register(provider)
        assertTrue(provider.isManaged)
        registry.releaseAll()
        registry.releaseAll()

        assertTrue(provider.isClosed)
        assertTrue(cursor.isReleased)
        assertFalse(provider.isManaged)
    }

    @Test
    fun `transferred provider is not released by the registry`() {
        val registry = StreamingResourceRegistry()
        val provider = RecordingProvider()
        registry.register(provider)

        assertTrue(provider.isManaged)
        assertTrue(registry.transfer(provider))
        assertFalse(provider.isManaged)
        registry.releaseAll()

        assertFalse(provider.closed.get())
        assertFalse(provider.resourcesReleased.get())
        provider.releaseResources()
    }

    @Test
    fun `provider registered after release is cleaned immediately`() {
        val registry = StreamingResourceRegistry()
        registry.releaseAll()
        val provider = RecordingProvider()

        registry.register(provider)

        assertTrue(provider.closed.get())
        assertTrue(provider.resourcesReleased.get())
    }

    @Test
    fun `shared provider is cleaned only after its last registry releases it`() {
        val firstRegistry = StreamingResourceRegistry()
        val secondRegistry = StreamingResourceRegistry()
        val provider = RecordingProvider()
        firstRegistry.register(provider)
        secondRegistry.register(provider)

        firstRegistry.releaseAll()

        assertTrue(provider.isManaged)
        assertFalse(provider.closed.get())
        assertFalse(provider.resourcesReleased.get())

        secondRegistry.releaseAll()

        assertFalse(provider.isManaged)
        assertEquals(1, provider.closeCount.get())
        assertEquals(1, provider.releaseCount.get())
    }

    @Test
    fun `shared provider cannot be transferred while another registry owns it`() {
        val firstRegistry = StreamingResourceRegistry()
        val secondRegistry = StreamingResourceRegistry()
        val provider = RecordingProvider()
        firstRegistry.register(provider)
        secondRegistry.register(provider)

        assertFalse(firstRegistry.transfer(provider))

        firstRegistry.releaseAll()
        secondRegistry.releaseAll()
        assertEquals(1, provider.releaseCount.get())
    }

    @Test
    fun `distinct equal providers are tracked by identity`() {
        val registry = StreamingResourceRegistry()
        val first = RecordingProvider(equalityKey = "equal-provider")
        val second = RecordingProvider(equalityKey = "equal-provider")
        assertTrue(first == second)

        registry.register(first)
        registry.register(second)
        registry.releaseAll()

        assertEquals(1, first.releaseCount.get())
        assertEquals(1, second.releaseCount.get())
        assertFalse(first.isManaged)
        assertFalse(second.isManaged)
    }

    @Test
    fun `cleanup continues after a provider throws`() {
        val registry = StreamingResourceRegistry()
        val failing = RecordingProvider(failOnClose = true, failOnRelease = true)
        val healthy = RecordingProvider()
        registry.register(failing)
        registry.register(healthy)

        val failure = assertFailsWith<StreamingCleanupException> { registry.releaseAll() }

        assertEquals(2, failure.failures.size)
        assertTrue(failing.closeAttempted.get())
        assertTrue(failing.releaseAttempted.get())
        assertTrue(healthy.closed.get())
        assertTrue(healthy.resourcesReleased.get())
    }

    @Test
    fun `concurrent registration and release cleans every provider exactly once`() {
        val registry = StreamingResourceRegistry()
        val providers = ConcurrentLinkedQueue<RecordingProvider>()
        val workers = 8
        val registrationsPerWorker = 200
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers + 1)

        repeat(workers) {
            executor.submit {
                start.await()
                repeat(registrationsPerWorker) {
                    val provider = RecordingProvider()
                    providers += provider
                    registry.register(provider)
                }
            }
        }
        executor.submit {
            start.await()
            registry.releaseAll()
        }

        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        registry.releaseAll()

        assertEquals(workers * registrationsPerWorker, providers.size)
        providers.forEach { provider ->
            assertEquals(1, provider.closeCount.get())
            assertEquals(1, provider.releaseCount.get())
            assertFalse(provider.isManaged)
        }
    }

    private class RecordingProvider(
        private val failOnClose: Boolean = false,
        private val failOnRelease: Boolean = false,
        private val equalityKey: String? = null,
    ) : MuseCursorStreamProvider {
        val closed = AtomicBoolean(false)
        val resourcesReleased = AtomicBoolean(false)
        val closeAttempted = AtomicBoolean(false)
        val releaseAttempted = AtomicBoolean(false)
        val closeCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)

        override val isClosed: Boolean
            get() = closed.get()

        override fun openCursor(): MuseCursorStream = throw UnsupportedOperationException()

        override fun close() {
            closeAttempted.set(true)
            if (closed.compareAndSet(false, true)) {
                closeCount.incrementAndGet()
            }
            if (failOnClose) throw IllegalStateException("close failure")
        }

        override fun releaseResources() {
            releaseAttempted.set(true)
            if (resourcesReleased.compareAndSet(false, true)) {
                releaseCount.incrementAndGet()
            }
            if (failOnRelease) throw IllegalStateException("release failure")
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (equalityKey != null && other is RecordingProvider && equalityKey == other.equalityKey)

        override fun hashCode(): Int = equalityKey?.hashCode() ?: System.identityHashCode(this)
    }
}

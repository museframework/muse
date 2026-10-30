package dev.museframework.engine.api.streaming

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

class ManagedStreamingProviderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `abandoned replay body is reclaimed while execution registry remains active`() {
        val registry = StreamingResourceRegistry()
        try {
            val reference = abandonedBody(registry)
            assertEquals(1, fileCount())
            awaitCollection { reference.get() == null && fileCount() == 0L }
            assertNull(reference.get())
            assertFalse(registry.isReleased)
            assertEquals(0, fileCount())
        } finally {
            registry.releaseAll()
        }
    }

    @Test
    fun `open cursor pins replay body and closed cursor does not retain its managed owner`() {
        val registry = StreamingResourceRegistry()
        val (reference, cursor) = bodyCursor(registry)
        try {
            repeat(3) { System.gc(); Thread.sleep(15) }
            assertNotNull(reference.get())
            assertEquals(7, cursor.read())
            assertEquals(1, fileCount())
            cursor.close()
            awaitCollection { reference.get() == null && fileCount() == 0L }
            assertNull(reference.get())
            assertEquals(0, fileCount())
        } finally {
            cursor.close()
            registry.releaseAll()
        }
    }

    @Test
    fun `independent response lease pins body until the lease closes`() {
        val registry = StreamingResourceRegistry()
        val (reference, lease) = bodyLease(registry)
        try {
            registry.releaseAll()
            repeat(3) { System.gc(); Thread.sleep(15) }
            assertNotNull(reference.get())
            assertEquals(1, fileCount())
            lease.close()
            awaitCollection { reference.get() == null && fileCount() == 0L }
            assertEquals(0, fileCount())
        } finally {
            lease.close()
            registry.releaseAll()
        }
    }

    @Test
    fun `released registry refuses an adopted delegate without closing its active handle`() {
        val raw = RepeatableInMemoryCursorStreamProvider.fromBytes("ack".toByteArray())
        val handle = StreamingProviders.managed(raw)
        val active = StreamingResourceRegistry()
        val released = StreamingResourceRegistry()
        active.register(handle)
        released.releaseAll()
        try {
            assertFailsWith<IllegalArgumentException> { released.register(raw) }
            assertFalse(handle.isClosed)
            assertEquals("ack", handle.openCursor().use { it.readBytes().decodeToString() })
        } finally {
            active.releaseAll()
        }
    }

    @Test
    fun `managed adoption is canonical and rejects already owned raw providers`() {
        val raw = RepeatableInMemoryCursorStreamProvider.fromBytes("ack".toByteArray())
        val handle = StreamingProviders.managed(raw)
        try {
            assertSame(handle, StreamingProviders.managed(raw))
            assertSame(handle, StreamingProviders.managed(handle))
            val registry = StreamingResourceRegistry()
            assertFailsWith<IllegalArgumentException> { registry.register(raw) }
            registry.register(handle)
            registry.releaseAll()
        } finally { handle.close() }

        val unadopted = RepeatableInMemoryCursorStreamProvider.fromBytes("raw".toByteArray())
        val registry = StreamingResourceRegistry()
        registry.register(unadopted)
        try { assertFailsWith<IllegalStateException> { StreamingProviders.managed(unadopted) } }
        finally { registry.releaseAll() }
    }

    @Test
    fun `shared managed claims survive parent release and transfer does not close the body`() {
        val provider = StreamingProviders.byteProvider(ByteArrayInputStream("shared".toByteArray()))
        val root = StreamingResourceRegistry()
        val branch = StreamingResourceRegistry()
        root.register(provider)
        branch.register(provider)
        root.releaseAll()
        assertFalse(provider.isClosed)
        assertEquals("shared", provider.openCursor().use { it.readBytes().decodeToString() })
        assertTrue(branch.transfer(provider))
        branch.releaseAll()
        assertFalse(provider.isClosed)
        assertFalse(provider.isManaged)
        val next = StreamingResourceRegistry()
        next.register(provider)
        next.releaseAll()
        assertTrue(provider.isClosed)
    }

    @Test
    fun `registering a released handle cannot recreate an ownership entry`() {
        val registry = StreamingResourceRegistry()
        val provider = registry.register(StreamingProviders.byteProvider(ByteArrayInputStream("done".toByteArray())))
        registry.releaseAll()
        val baseline = StreamingResourceRegistry.statistics()
        repeat(20) { registry.register(provider) }
        assertEquals(baseline, StreamingResourceRegistry.statistics())
    }

    @Test
    fun `custom container alias protects iterator without traversing its values`() {
        val registry = StreamingResourceRegistry()
        val probe = IteratorProbe()
        val (reference, holder) = iteratorAlias(registry, probe)
        try {
            repeat(3) { System.gc(); Thread.sleep(15) }
            assertNotNull(reference.get())
            assertEquals(0, probe.opens.get())
            assertEquals(listOf(1, 2), holder.value!!.openCursor().use { it.asSequence().toList() })
            holder.value = null
            awaitCollection { reference.get() == null && probe.releases.get() == 1 }
            assertEquals(1, probe.closes.get())
            assertEquals(1, probe.releases.get())
        } finally { registry.releaseAll() }
    }

    @Test
    fun `managed iterator preserves background preparation capability`() = runBlocking {
        val probe = IteratorProbe()
        val handle = StreamingProviders.managed(probe)
        try {
            assertTrue(handle is BackgroundStreamPreparation)
            (handle as BackgroundStreamPreparation).prepareForBackgroundUse()
            assertEquals(1, probe.preparations.get())
            assertEquals(0, probe.opens.get())
            assertSame(handle, handle.openCursor().use { it.provider })
        } finally { handle.close() }
        assertEquals(1, probe.closes.get())
        assertEquals(1, probe.releases.get())
    }

    @Test
    fun `released managed provider preserves its body size metadata`() {
        val handle = StreamingProviders.byteProvider(ByteArrayInputStream("metadata".toByteArray()))
        handle.close()
        assertEquals(8L, handle.size)
        assertTrue(handle.isClosed)
    }

    @Test
    fun `cleanup does not initialize lazy size metadata of an unread provider`() {
        val metadataReads = AtomicInteger()
        val delegate = NonRepeatableCursorStreamProvider(ByteArrayInputStream("lazy".toByteArray()))
        val raw = object : MuseCursorStreamProvider by delegate {
            override val size: Long get() { metadataReads.incrementAndGet(); return 4L }
        }
        val handle = StreamingProviders.managed(raw)
        handle.close()
        assertEquals(0, metadataReads.get())
        assertTrue(delegate.isClosed)
    }

    @Test
    fun `cleaner and explicit registry release clean an abandoned body exactly once`() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val closes = AtomicInteger()
        val releases = AtomicInteger()
        val backing = RepeatableInMemoryCursorStreamProvider.fromBytes("race".toByteArray())
        val raw = object : MuseCursorStreamProvider by backing {
            override fun close() {
                closes.incrementAndGet()
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
                backing.close()
            }
            override fun releaseResources() { releases.incrementAndGet(); backing.releaseResources() }
        }
        val registry = StreamingResourceRegistry()
        val reference = abandonRaw(registry, raw)
        try {
            awaitCollection { entered.count == 0L }
            registry.releaseAll()
            assertEquals(1, closes.get())
            proceed.countDown()
            awaitCollection { reference.get() == null && releases.get() == 1 && !raw.isManaged }
            registry.releaseAll()
            assertEquals(1, closes.get())
            assertEquals(1, releases.get())
        } finally {
            proceed.countDown()
            registry.releaseAll()
        }
    }

    @Test
    fun `adoption cannot recreate a handle while its collected reference awaits cleanup`() {
        val raw = RepeatableInMemoryCursorStreamProvider.fromBytes("pending".toByteArray())
        val handle = StreamingProviders.managed(raw)
        // Reproduce the ReferenceQueue interval without waiting for collector scheduling.
        (handle as ManagedStreamingHandle).cleanupState.handle.clear()
        try {
            assertFailsWith<StreamingResourceReleasedException> { StreamingProviders.managed(raw) }
            assertFalse(raw.isClosed)
        } finally { handle.close() }
    }

    private fun abandonRaw(registry: StreamingResourceRegistry, raw: MuseCursorStreamProvider): WeakReference<MuseCursorStreamProvider> {
        val handle = registry.register(StreamingProviders.managed(raw))
        return WeakReference(handle)
    }

    private fun iteratorAlias(registry: StreamingResourceRegistry, probe: IteratorProbe): Pair<WeakReference<MuseCursorIteratorProvider<Int>>, OpaqueHolder> {
        val handle = registry.register(StreamingProviders.managed(probe))
        return WeakReference(handle) to OpaqueHolder(handle)
    }

    private class OpaqueHolder(var value: MuseCursorIteratorProvider<Int>?)

    private class IteratorProbe : MuseCursorIteratorProvider<Int>, BackgroundStreamPreparation {
        private val delegate = RepeatableInMemoryCursorIteratorProvider.fromIterable(listOf(1, 2))
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val releases = AtomicInteger()
        val preparations = AtomicInteger()
        override val isClosed: Boolean get() = delegate.isClosed
        override fun openCursor(): MuseCursorIterator<Int> {
            opens.incrementAndGet()
            return delegate.openCursor()
        }
        override fun close() { closes.incrementAndGet(); delegate.close() }
        override fun releaseResources() { releases.incrementAndGet(); delegate.releaseResources() }
        override suspend fun prepareForBackgroundUse() { preparations.incrementAndGet() }
    }

    private fun provider(): MuseCursorStreamProvider =
        StreamingProviders.byteProvider(
            ByteArrayInputStream(ByteArray(8192) { 7 }),
            StreamingStrategy.RepeatableFileStore(inMemorySizeBytes = 128, tempDirectory = directory.toString()),
        )

    private fun abandonedBody(registry: StreamingResourceRegistry): WeakReference<MuseCursorStreamProvider> {
        val provider = registry.register(provider())
        return WeakReference(provider)
    }

    private fun bodyCursor(registry: StreamingResourceRegistry): Pair<WeakReference<MuseCursorStreamProvider>, MuseCursorStream> {
        val provider = registry.register(provider())
        return WeakReference(provider) to provider.openCursor()
    }

    private fun bodyLease(registry: StreamingResourceRegistry): Pair<WeakReference<MuseCursorStreamProvider>, StreamingResourceLease> {
        val provider = registry.register(provider())
        return WeakReference(provider) to registry.retain(listOf(provider))
    }

    private fun fileCount(): Long = Files.list(directory).use { it.count() }

    private fun awaitCollection(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(15)
        }
        assertTrue(condition(), "Abandoned managed body was retained or its replay file was not released")
    }
}

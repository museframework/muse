package dev.museframework.engine.api.streaming

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingRegistryDrainTest {
    @Test
    fun `large execution cleanup avoids allocating an iterator for every provider`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val count = AtomicInteger()
        val warmup = StreamingResourceRegistry()
        repeat(100) { warmup.register(EmptyProvider(count)) }
        warmup.releaseAll()
        count.set(0)
        val registry = StreamingResourceRegistry()
        repeat(10_000) { registry.register(EmptyProvider(count)) }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        registry.releaseAll()
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertEquals(20_000, count.get())
        assertTrue(allocated < 256 * 1024, "Large-scope cleanup allocated $allocated bytes")
    }

    private class EmptyProvider(private val count: AtomicInteger) : MuseCursorProvider<MuseCursor> {
        override val isClosed: Boolean = false
        override fun openCursor(): MuseCursor = error("Cleanup must not open a cursor")
        override fun close() { count.incrementAndGet() }
        override fun releaseResources() { count.incrementAndGet() }
    }
}

package dev.museframework.engine.api.streaming

import com.sun.management.ThreadMXBean
import java.io.ByteArrayInputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileStoreBufferAllocationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `tiny repeatable bodies do not allocate the full memory threshold`() {
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(allocationBean != null && allocationBean.isThreadAllocatedMemorySupported)
        val bean = requireNotNull(allocationBean)
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        val bodies = Array(100) { index -> ByteArray(index + 1) { 7 } }

        // Warm up class loading before measuring allocations made by body acquisition.
        repeat(100) { index -> acquireAndRelease(bodies[index % bodies.size]) }
        val threadId = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(threadId)
        var bytesRead = 0
        repeat(1000) { index -> bytesRead += acquireAndRelease(bodies[index % bodies.size]) }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before

        assertEquals(50_500, bytesRead)
        assertTrue(allocated >= 0, "Thread allocation accounting must return a valid measurement")
        assertTrue(
            allocated < 16L * 1024 * 1024,
            "Acquiring 1000 bodies of 1-100 bytes allocated $allocated bytes; tiny responses must not reserve the full file-store threshold",
        )
    }

    @Test
    fun `growing memory buffer retains the configured spill boundary and repeatability`() {
        val memoryThreshold = 32 * 1024
        val bytes = ByteArray(memoryThreshold + 8192) { index -> (index % 251).toByte() }
        val provider = RepeatableFileStoreCursorStreamProvider.fromInputStream(
            ByteArrayInputStream(bytes),
            inMemorySizeBytes = memoryThreshold,
            tempDirectory = tempDir,
        )
        try {
            assertEquals(bytes.size.toLong(), provider.size)
            val spill = Files.list(tempDir).use { it.findFirst().orElseThrow() }
            assertEquals((bytes.size - memoryThreshold).toLong(), Files.size(spill))
            repeat(2) { provider.openCursor().use { cursor -> assertContentEquals(bytes, cursor.readBytes()) } }
        } finally {
            provider.releaseResources()
        }
        assertEquals(0L, Files.list(tempDir).use { it.count() })
    }

    private fun acquireAndRelease(body: ByteArray): Int {
        val provider = RepeatableFileStoreCursorStreamProvider.fromInputStream(ByteArrayInputStream(body))
        try {
            return provider.openCursor().use { cursor ->
                var read = 0
                while (cursor.read() != -1) read++
                read
            }
        } finally {
            provider.releaseResources()
        }
    }
}

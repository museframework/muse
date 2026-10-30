package dev.museframework.engine.api.streaming

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepeatableFileStoreCursorStreamProviderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `keeps small streams in memory without temp file`() {
        val provider = RepeatableFileStoreCursorStreamProvider.fromInputStream(
            inputStream = ByteArrayInputStream("abc".toByteArray()),
            inMemorySizeBytes = 10,
            tempDirectory = tempDir,
        )

        assertEquals(3L, provider.size)
        assertFalse(Files.list(tempDir).use { files -> files.anyMatch { it.fileName.toString().startsWith("muse-stream-") } })
        assertEquals("abc", provider.openCursor().readBytes().decodeToString())
        provider.releaseResources()
    }

    @Test
    fun `spills large streams to temp file and opens repeatable cursors`() {
        val provider = RepeatableFileStoreCursorStreamProvider.fromInputStream(
            inputStream = ByteArrayInputStream("abcdef".toByteArray()),
            inMemorySizeBytes = 3,
            tempDirectory = tempDir,
        )

        val first = provider.openCursor()
        val second = provider.openCursor()

        assertContentEquals("abcdef".toByteArray(), first.readBytes())
        assertContentEquals("abcdef".toByteArray(), second.readBytes())
        assertTrue(Files.list(tempDir).use { files -> files.anyMatch { it.fileName.toString().startsWith("muse-stream-") } })
        provider.releaseResources()
        assertFalse(Files.list(tempDir).use { files -> files.anyMatch { it.fileName.toString().startsWith("muse-stream-") } })
    }

    @Test
    fun `release deletes temp file when configured`() {
        val provider = RepeatableFileStoreCursorStreamProvider.fromInputStream(
            inputStream = ByteArrayInputStream("abcdef".toByteArray()),
            inMemorySizeBytes = 3,
            tempDirectory = tempDir,
            deleteFileOnClose = true,
        )
        val tempFile = Files.list(tempDir).use { files -> files.filter { it.fileName.toString().startsWith("muse-stream-") }.findFirst().orElseThrow() }
        provider.releaseResources()

        assertFalse(tempFile.exists())
    }

    @Test
    fun `close deletes temp file when configured`() {
        val provider = RepeatableFileStoreCursorStreamProvider.fromInputStream(
            inputStream = ByteArrayInputStream("abcdef".toByteArray()),
            inMemorySizeBytes = 3,
            tempDirectory = tempDir,
            deleteFileOnClose = true,
        )
        val tempFile = Files.list(tempDir).use { files -> files.filter { it.fileName.toString().startsWith("muse-stream-") }.findFirst().orElseThrow() }

        provider.close()

        assertFalse(tempFile.exists())
    }
}

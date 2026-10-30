package dev.museframework.engine.api.streaming

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImmutableFileCursorStreamProviderTest {

    @Test
    fun `opens repeatable cursors over the original file`() {
        val file = Files.createTempFile("muse-immutable-", ".txt")
        file.writeText("abcdef")
        val provider = ImmutableFileCursorStreamProvider(file)

        assertEquals("abcdef", provider.openCursor().readBytes().decodeToString())
        assertEquals("abcdef", provider.openCursor().readBytes().decodeToString())
        assertTrue(file.exists())
    }

    @Test
    fun `release deletes original file when configured`() {
        val file = Files.createTempFile("muse-immutable-", ".txt")
        file.writeText("abcdef")
        val provider = ImmutableFileCursorStreamProvider(file, deleteFileOnClose = true)

        provider.releaseResources()

        assertFalse(file.exists())
    }

    @Test
    fun `close deletes original file when configured`() {
        val file = Files.createTempFile("muse-immutable-", ".txt")
        file.writeText("abcdef")
        val provider = ImmutableFileCursorStreamProvider(file, deleteFileOnClose = true)

        provider.close()

        assertFalse(file.exists())
    }

    @Test
    fun `cursor preserves positions beyond two gibibytes`() {
        val file = Files.createTempFile("muse-immutable-large-", ".bin")
        val targetPosition = Int.MAX_VALUE.toLong() + 17L
        Files.newByteChannel(file, StandardOpenOption.WRITE).use { channel ->
            channel.position(targetPosition)
            channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0x5a)))
        }
        val provider = ImmutableFileCursorStreamProvider(file, deleteFileOnClose = true)

        try {
            val cursor = provider.openCursor()
            cursor.seek(targetPosition)

            assertEquals(targetPosition, cursor.position)
            assertEquals(0x5a, cursor.read())
            assertEquals(targetPosition + 1L, cursor.position)
        } finally {
            provider.releaseResources()
        }

        assertFalse(file.exists())
    }
}

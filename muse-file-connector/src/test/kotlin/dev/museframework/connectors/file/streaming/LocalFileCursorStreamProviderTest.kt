package dev.museframework.connectors.file.streaming

import dev.museframework.connectors.file.internal.FileLockRegistry
import dev.museframework.engine.api.streaming.StreamingResourceReleasedException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalFileCursorStreamProviderTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `provider opens repeatable seekable cursors without materializing the file`() {
        val file = directory.resolve("value.txt").also { it.writeText("abcdef") }
        val provider = LocalFileCursorStreamProvider(file, lock = false)
        val first = provider.openCursor()
        val second = provider.openCursor()

        assertEquals("abc", first.readNBytes(3).decodeToString())
        first.seek(1)
        assertEquals("bc", first.readNBytes(2).decodeToString())
        assertEquals("abcdef", second.readAllBytes().decodeToString())

        first.close()
        second.close()
        provider.releaseResources()
        assertTrue(provider.isClosed)
    }

    @Test
    fun `release invalidates every open cursor`() {
        val file = directory.resolve("value.txt").also { it.writeText("abcdef") }
        val provider = LocalFileCursorStreamProvider(file, lock = false)
        val cursor = provider.openCursor()

        provider.releaseResources()

        assertFailsWith<StreamingResourceReleasedException> { cursor.read() }
    }

    @Test
    fun `file lock is released when the last cursor closes`() {
        val file = directory.resolve("locked.txt").also { it.writeText("payload") }
        val registry = FileLockRegistry()
        val provider = LocalFileCursorStreamProvider(file, lock = true, lockRegistry = registry)
        val cursor = provider.openCursor()

        assertTrue(registry.isLocked(file))
        cursor.close()
        assertFalse(registry.isLocked(file))
    }

    @Test
    fun `file lock stays held after the first cursor reaches EOF`() {
        val file = directory.resolve("locked.txt").also { it.writeText("ab") }
        val registry = FileLockRegistry()
        val provider = LocalFileCursorStreamProvider(file, lock = true, lockRegistry = registry)
        val first = provider.openCursor()
        val second = provider.openCursor()

        assertEquals("ab", first.readBytes().decodeToString())
        assertTrue(registry.isLocked(file))
        first.close()
        assertTrue(registry.isLocked(file))
        second.close()
        assertFalse(registry.isLocked(file))
    }

    @Test
    fun `read lock does not require write permission`() {
        val file = directory.resolve("locked.txt").also { it.writeText("ab") }
        val readOnly =
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            )
        val changed = runCatching { java.nio.file.Files.setPosixFilePermissions(file, readOnly) }.isSuccess
        org.junit.jupiter.api.Assumptions.assumeTrue(changed)
        val registry = FileLockRegistry()
        val provider = LocalFileCursorStreamProvider(file, lock = true, lockRegistry = registry)

        val cursor = provider.openCursor()
        assertEquals("ab", cursor.readBytes().decodeToString())
        assertTrue(registry.isLocked(file))
        cursor.close()
        assertFalse(registry.isLocked(file))
    }
}

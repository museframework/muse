package dev.museframework.connectors.file.internal

import dev.museframework.file.api.config.FileConnectionConfig
import dev.museframework.file.api.model.FileErrorType
import dev.museframework.file.api.model.FileListSortCriteria
import dev.museframework.file.api.model.FileTypeRequirement
import dev.museframework.file.api.model.FileWriteMode
import dev.museframework.file.api.model.LocalFileMatcher
import dev.museframework.file.api.model.LocalSubsetList
import dev.museframework.file.api.model.SortOrder
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalFileSystemTest {
    @TempDir
    lateinit var workingDirectory: Path

    private fun fileSystem(): LocalFileSystem =
        LocalFileSystem(FileConnectionConfig(workingDirectory = workingDirectory.toString()))

    @Test
    fun `list filters recursively and applies subset after sorting`() {
        val input = workingDirectory.resolve("input").createDirectories()
        val nested = input.resolve("nested").createDirectories()
        input.resolve("small.txt").writeText("a")
        input.resolve("ignored.bin").writeText("123456789")
        nested.resolve("large.txt").writeText("12345")
        val old = Instant.now().minus(1, ChronoUnit.HOURS)
        Files.setLastModifiedTime(input.resolve("small.txt"), FileTime.from(old))

        val messages =
            fileSystem().list(
                directoryExpression = "input",
                recursive = true,
                matcher =
                    LocalFileMatcher(
                        filenamePattern = "*.txt",
                        regularFiles = FileTypeRequirement.REQUIRE,
                    ),
                timeBetweenSizeCheck = null,
                timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                subset =
                    LocalSubsetList(
                        limit = 1,
                        criteria = FileListSortCriteria.SIZE,
                        order = SortOrder.DESCENDING,
                    ),
            )

        assertEquals(1, messages.size)
        assertEquals("large.txt", messages.single().attributes.fileName)
        assertEquals("12345", messages.single().payload!!.openCursor().bufferedReader().use { it.readText() })
        messages.single().payload!!.releaseResources()
    }

    @Test
    fun `list subset returns the alphabetical page by offset`() {
        val input = workingDirectory.resolve("input").createDirectories()
        input.resolve("a.txt").writeText("a")
        input.resolve("b.txt").writeText("b")
        input.resolve("c.txt").writeText("c")

        val messages =
            fileSystem().list(
                directoryExpression = "input",
                recursive = false,
                matcher = null,
                timeBetweenSizeCheck = null,
                timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                subset =
                    LocalSubsetList(
                        limit = 1,
                        offset = 1,
                        criteria = FileListSortCriteria.ALPHABETICALLY,
                        order = SortOrder.ASCENDING,
                    ),
            )

        assertEquals(listOf("b.txt"), messages.map { it.attributes.fileName })
        messages.forEach { it.payload?.releaseResources() }
    }

    @Test
    fun `non recursive list includes direct directories without their contents`() {
        val input = workingDirectory.resolve("input").createDirectories()
        input.resolve("top.txt").writeText("top")
        input.resolve("nested").createDirectories().resolve("hidden.txt").writeText("hidden")

        val messages =
            fileSystem().list(
                directoryExpression = "input",
                recursive = false,
                matcher = null,
                timeBetweenSizeCheck = null,
                timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                subset = null,
            )

        assertEquals(setOf("top.txt", "nested"), messages.map { it.attributes.fileName }.toSet())
        assertNull(messages.single { it.attributes.directory }.payload)
        messages.mapNotNull { it.payload }.forEach { it.releaseResources() }
    }

    @Test
    fun `copy move rename and delete preserve recursive directory contents`() {
        val source = workingDirectory.resolve("source").createDirectories()
        source.resolve("nested").createDirectories().resolve("value.txt").writeText("value")
        val fileSystem = fileSystem()

        fileSystem.copy("source", "copies", createParentDirectories = true, overwrite = false, renameTo = null)
        assertEquals("value", workingDirectory.resolve("copies/source/nested/value.txt").readText())

        fileSystem.move("copies/source", "archive", createParentDirectories = true, overwrite = false, renameTo = "moved")
        assertFalse(workingDirectory.resolve("copies/source").exists())
        assertEquals("value", workingDirectory.resolve("archive/moved/nested/value.txt").readText())

        fileSystem.rename("archive/moved", "renamed", overwrite = false)
        assertTrue(workingDirectory.resolve("archive/renamed").exists())

        fileSystem.delete("archive/renamed")
        assertFalse(workingDirectory.resolve("archive/renamed").exists())
    }

    @Test
    fun `copy refuses existing destination unless overwrite is enabled`() {
        workingDirectory.resolve("source.txt").writeText("new")
        workingDirectory.resolve("target").createDirectories().resolve("source.txt").writeText("old")
        val fileSystem = fileSystem()

        val failure =
            assertFailsWith<FileConnectorException> {
                fileSystem.copy("source.txt", "target", true, false, null)
            }
        assertEquals(FileErrorType.FILE_ALREADY_EXISTS, failure.errorType)

        fileSystem.copy("source.txt", "target", true, true, null)
        assertEquals("new", workingDirectory.resolve("target/source.txt").readText())
    }

    @Test
    fun `create directory rejects an existing path`() {
        val fileSystem = fileSystem()
        fileSystem.createDirectory("new/child")
        assertTrue(workingDirectory.resolve("new/child").exists())

        val failure = assertFailsWith<FileConnectorException> { fileSystem.createDirectory("new/child") }
        assertEquals(FileErrorType.FILE_ALREADY_EXISTS, failure.errorType)
    }

    @Test
    fun `working directory must exist and be a directory`() {
        val missing = workingDirectory.resolve("missing")
        val missingFailure =
            assertFailsWith<FileConnectorException> {
                LocalFileSystem(FileConnectionConfig(workingDirectory = missing.toString()))
            }
        assertEquals(FileErrorType.FILE_DOESNT_EXIST, missingFailure.errorType)

        val file = workingDirectory.resolve("file.txt").also { it.writeText("x") }
        val fileFailure =
            assertFailsWith<FileConnectorException> {
                LocalFileSystem(FileConnectionConfig(workingDirectory = file.toString()))
            }
        assertEquals(FileErrorType.FILE_IS_NOT_DIRECTORY, fileFailure.errorType)
    }

    @Test
    fun `read of a missing relative path fails with illegal path`() {
        val failure =
            assertFailsWith<FileConnectorException> {
                fileSystem().read(
                    pathExpression = "missing.txt",
                    lock = false,
                    timeBetweenSizeCheck = null,
                    timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                    outputMimeType = null,
                )
            }
        assertEquals(FileErrorType.ILLEGAL_PATH, failure.errorType)
    }

    @Test
    fun `list of a regular file fails with illegal path`() {
        workingDirectory.resolve("file.txt").writeText("x")
        val failure =
            assertFailsWith<FileConnectorException> {
                fileSystem().list(
                    directoryExpression = "file.txt",
                    recursive = false,
                    matcher = null,
                    timeBetweenSizeCheck = null,
                    timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                    subset = null,
                )
            }
        assertEquals(FileErrorType.ILLEGAL_PATH, failure.errorType)
    }

    @Test
    fun `list defers size stability checks until content is retrieved`() {
        workingDirectory.resolve("growing.txt").writeText("a")
        val fileSystem = fileSystem()

        val started = System.nanoTime()
        val messages =
            fileSystem.list(
                directoryExpression = ".",
                recursive = false,
                matcher = LocalFileMatcher(filenamePattern = "growing.txt"),
                timeBetweenSizeCheck = 200,
                timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                subset = null,
            )
        val listElapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(listElapsedMs < 150, "list should not wait for size stability; took ${listElapsedMs}ms")
        assertEquals(1, messages.size)

        val contentStarted = System.nanoTime()
        assertEquals("a", messages.single().payload!!.openCursor().bufferedReader().use { it.readText() })
        val contentElapsedMs = (System.nanoTime() - contentStarted) / 1_000_000
        assertTrue(contentElapsedMs >= 150, "content access should wait for size stability; took ${contentElapsedMs}ms")
        messages.single().payload!!.releaseResources()
    }

    @Test
    fun `delete refuses a file that is currently locked by a read`() {
        workingDirectory.resolve("locked.txt").writeText("payload")
        val fileSystem = fileSystem()
        val provider =
            fileSystem.read(
                pathExpression = "locked.txt",
                lock = true,
                timeBetweenSizeCheck = null,
                timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                outputMimeType = null,
            ).provider
        provider.openCursor()

        val failure = assertFailsWith<FileConnectorException> { fileSystem.delete("locked.txt") }
        assertEquals(FileErrorType.FILE_LOCK, failure.errorType)

        provider.releaseResources()
        fileSystem.delete("locked.txt")
        assertFalse(workingDirectory.resolve("locked.txt").exists())
    }

    @Test
    fun `reopened locked read blocks deletion until its cursor closes`() {
        val file = workingDirectory.resolve("locked.txt").also {
            it.writeText("payload")
        }
        val fileSystem = fileSystem()
        val provider =
            fileSystem.read(
                pathExpression = "locked.txt",
                lock = true,
                timeBetweenSizeCheck = null,
                timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                outputMimeType = null,
            ).provider

        try {
            provider.openCursor().use { first ->
                assertEquals('p'.code, first.read())
            }
            assertFalse(provider.isClosed)

            provider.openCursor().use { reopened ->
                val failure =
                    assertFailsWith<FileConnectorException> {
                        fileSystem.delete("locked.txt")
                    }
                assertEquals(FileErrorType.FILE_LOCK, failure.errorType)
                assertEquals("payload", reopened.readAllBytes().decodeToString())
            }

            assertFalse(provider.isClosed)
            fileSystem.delete("locked.txt")
            assertFalse(file.exists())
        } finally {
            provider.releaseResources()
        }
    }

    @Test
    fun `reopened locked read fails while an exclusive lock is held then succeeds after release`() {
        val file = workingDirectory.resolve("locked.txt").also {
            it.writeText("payload")
        }
        val fileSystem = fileSystem()
        val provider =
            fileSystem.read(
                pathExpression = "locked.txt",
                lock = true,
                timeBetweenSizeCheck = null,
                timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                outputMimeType = null,
            ).provider

        try {
            provider.openCursor().use { first ->
                assertEquals('p'.code, first.read())
            }
            assertFalse(provider.isClosed)

            FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val failure = assertFailsWith<FileConnectorException> { provider.openCursor() }
                    assertEquals(FileErrorType.FILE_LOCK, failure.errorType)
                }
            }

            provider.openCursor().use { reopened ->
                val failure =
                    assertFailsWith<FileConnectorException> {
                        fileSystem.delete("locked.txt")
                    }
                assertEquals(FileErrorType.FILE_LOCK, failure.errorType)
                assertEquals("payload", reopened.readAllBytes().decodeToString())
            }

            assertFalse(provider.isClosed)
            fileSystem.delete("locked.txt")
            assertFalse(file.exists())
        } finally {
            provider.releaseResources()
        }
    }

    @Test
    fun `overwrite lock failure keeps the previous bytes`() {
        val target = workingDirectory.resolve("locked.txt").also { it.writeText("original") }
        FileChannel.open(target, StandardOpenOption.WRITE).use { channel ->
            val held = checkNotNull(channel.tryLock())
            try {
                val failure =
                    runCatching {
                        fileSystem().write(
                            pathExpression = "locked.txt",
                            content = "replacement",
                            createParentDirectories = true,
                            lock = true,
                            mode = FileWriteMode.OVERWRITE,
                            charset = Charsets.UTF_8,
                        )
                    }
                assertTrue(failure.isFailure)
                assertEquals("original", target.readText())
            } finally {
                held.release()
            }
        }
    }

    @Test
    fun `failed replacement restores the previous destination`() {
        val destination = workingDirectory.resolve("dest").createDirectories()
        destination.resolve("keep.txt").writeText("keep")

        val failure =
            assertFailsWith<IllegalStateException> {
                fileSystem().replaceOrCreate(destination, overwrite = true) { error("boom") }
            }
        assertEquals("boom", failure.message)
        assertEquals("keep", destination.resolve("keep.txt").readText())
        assertFalse(workingDirectory.resolve("dest.muse-replacing").exists())
    }

    @Test
    fun `path pattern matches the path relative to the working directory`() {
        val inbox = workingDirectory.resolve("inbox").createDirectories()
        inbox.resolve("keep.txt").writeText("keep")
        inbox.resolve("skip.bin").writeText("skip")

        val names =
            fileSystem().list(
                directoryExpression = "inbox",
                recursive = false,
                matcher = LocalFileMatcher(pathPattern = "inbox/*.txt"),
                timeBetweenSizeCheck = null,
                timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                subset = null,
            ).map { it.attributes.fileName }

        assertEquals(listOf("keep.txt"), names)
    }

    @Test
    fun `unreadable path fails with access denied`() {
        val secret = workingDirectory.resolve("secret").createDirectories()
        secret.resolve("value.txt").writeText("hidden")
        val changed = runCatching { Files.setPosixFilePermissions(secret, emptySet()) }.isSuccess
        Assumptions.assumeTrue(changed)
        val denied =
            runCatching { Files.readAttributes(secret, BasicFileAttributes::class.java) }.exceptionOrNull()
        Assumptions.assumeTrue(denied is AccessDeniedException)
        try {
            val failure =
                assertFailsWith<FileConnectorException> {
                    fileSystem().read(
                        pathExpression = "secret/value.txt",
                        lock = false,
                        timeBetweenSizeCheck = null,
                        timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS,
                        outputMimeType = null,
                    )
                }
            assertEquals(FileErrorType.ACCESS_DENIED, failure.errorType)
        } finally {
            secret.toFile().setReadable(true)
            secret.toFile().setExecutable(true)
        }
    }

    @Test
    fun `write rejects content that is not bytes text or a stream`() {
        val target = workingDirectory.resolve("out.txt").also { it.writeText("original") }
        val failure =
            assertFailsWith<FileConnectorException> {
                fileSystem().write(
                    pathExpression = "out.txt",
                    content = listOf("nope"),
                    createParentDirectories = true,
                    lock = false,
                    mode = FileWriteMode.OVERWRITE,
                    charset = Charsets.UTF_8,
                )
            }
        assertEquals(FileErrorType.ILLEGAL_CONTENT, failure.errorType)
        assertEquals("original", target.readText())
    }
}

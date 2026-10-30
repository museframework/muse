package dev.museframework.connectors.file

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.file.api.dsl.FileListBuilder
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileList
import dev.museframework.file.api.dsl.fileWrite
import dev.museframework.file.api.model.FileListSortCriteria
import dev.museframework.file.api.model.FileTypeRequirement
import dev.museframework.file.api.model.LocalFileMatcher
import dev.museframework.file.api.model.LocalSubsetList
import dev.museframework.file.api.model.SortOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileListIT {
    @TempDir
    lateinit var workingDirectory: Path

    @Test
    fun `list writes direct child names and omits nested files`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/a.txt", "a")
        writeRelative(workingDirectory, "catalog/b.dat", "b")
        writeRelative(workingDirectory, "catalog/nested/c.txt", "c")
        val output = workingDirectory.resolve("output/list.txt")
        val flow =
            flow("list-direct") {
                pollingFileListener()
                fileList(fileConnection(FILE_CONNECTION_NAME)) {
                    directoryPath = "catalog"
                    recursive = false
                }
                writeListedNames("output/list.txt")
            }

        runFileApplication(workingDirectory, flow) {
            val listed = awaitListed(output)
            assertContains(listed, "a.txt")
            assertContains(listed, "b.dat")
            assertContains(listed, "nested")
            assertFalse(listed.contains("c.txt"))
        }
    }

    @Test
    fun `recursive list includes a nested file name`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/nested/c.txt", "c")
        val output = workingDirectory.resolve("output/list.txt")
        val flow =
            flow("list-recursive") {
                pollingFileListener()
                fileList(fileConnection(FILE_CONNECTION_NAME)) {
                    directoryPath = "catalog"
                    recursive = true
                }
                writeListedNames("output/list.txt")
            }

        runFileApplication(workingDirectory, flow) {
            assertContains(awaitListed(output), "c.txt")
        }
    }

    @Test
    fun `list matcher keeps only small text files`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/keep.txt", "ok")
        writeRelative(workingDirectory, "catalog/big.txt", "x".repeat(20))
        writeRelative(workingDirectory, "catalog/skip.dat", "no")
        val output = workingDirectory.resolve("output/list.txt")
        val flow =
            flow("list-matcher") {
                pollingFileListener()
                fileList(fileConnection(FILE_CONNECTION_NAME)) {
                    directoryPath = "catalog"
                    matcher = LocalFileMatcher(filenamePattern = "*.txt", maxSize = 10)
                }
                writeListedNames("output/list.txt")
            }

        runFileApplication(workingDirectory, flow) {
            val listed = awaitListed(output)
            assertContains(listed, "keep.txt")
            assertFalse(listed.contains("big.txt"))
            assertFalse(listed.contains("skip.dat"))
        }
    }

    @Test
    fun `list subset returns the alphabetical page`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/a.txt", "a")
        writeRelative(workingDirectory, "catalog/b.txt", "b")
        writeRelative(workingDirectory, "catalog/c.txt", "c")
        val output = workingDirectory.resolve("output/list.txt")
        val flow =
            flow("list-subset") {
                pollingFileListener()
                fileList(fileConnection(FILE_CONNECTION_NAME)) {
                    directoryPath = "catalog"
                    subset =
                        LocalSubsetList(
                            limit = 1,
                            offset = 1,
                            criteria = FileListSortCriteria.ALPHABETICALLY,
                            order = SortOrder.ASCENDING,
                        )
                }
                writeListedNames("output/list.txt")
            }

        runFileApplication(workingDirectory, flow) {
            val listed = awaitListed(output)
            assertContains(listed, "b.txt")
            assertFalse(listed.contains("a.txt"))
            assertFalse(listed.contains("c.txt"))
        }
    }

    @Test
    fun `list subset returns the largest file`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/a.txt", "a")
        writeRelative(workingDirectory, "catalog/b.txt", "bbbb")
        writeRelative(workingDirectory, "catalog/c.txt", "cc")
        val listed = listNames(recursive = false) {
            subset =
                LocalSubsetList(
                    limit = 1,
                    criteria = FileListSortCriteria.SIZE,
                    order = SortOrder.DESCENDING,
                )
        }

        assertContains(listed, "b.txt")
        assertFalse(listed.contains("a.txt"))
        assertFalse(listed.contains("c.txt"))
    }

    @Test
    fun `list subset returns the newest modified file`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val oldest = writeRelative(workingDirectory, "catalog/old.txt", "old")
        val middle = writeRelative(workingDirectory, "catalog/middle.txt", "middle")
        val newest = writeRelative(workingDirectory, "catalog/new.txt", "new")
        Files.setLastModifiedTime(oldest, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")))
        Files.setLastModifiedTime(middle, FileTime.from(Instant.parse("2022-01-01T00:00:00Z")))
        Files.setLastModifiedTime(newest, FileTime.from(Instant.parse("2024-01-01T00:00:00Z")))
        val listed = listNames {
            subset =
                LocalSubsetList(
                    limit = 1,
                    criteria = FileListSortCriteria.DATE_MODIFIED,
                    order = SortOrder.DESCENDING,
                )
        }

        assertContains(listed, "new.txt")
        assertFalse(listed.contains("old.txt"))
        assertFalse(listed.contains("middle.txt"))
    }

    @Test
    fun `list subset returns the first path`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/b/a.txt", "a")
        writeRelative(workingDirectory, "catalog/a/z.txt", "z")
        val listed = listNames(recursive = true) {
            matcher = LocalFileMatcher(directories = FileTypeRequirement.EXCLUDE)
            subset =
                LocalSubsetList(
                    limit = 1,
                    criteria = FileListSortCriteria.PATH,
                    order = SortOrder.ASCENDING,
                )
        }

        assertContains(listed, "z.txt")
        assertFalse(listed.contains("a.txt"))
    }

    @Test
    fun `list subset returns the newest created file when birth time can be set`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val older = writeRelative(workingDirectory, "catalog/old.txt", "old")
        val newer = writeRelative(workingDirectory, "catalog/new.txt", "new")
        Assumptions.assumeTrue(assignCreationTime(older, Instant.parse("2020-01-01T00:00:00Z")) != null)
        Assumptions.assumeTrue(assignCreationTime(newer, Instant.parse("2024-06-01T00:00:00Z")) != null)
        val listed = listNames {
            subset =
                LocalSubsetList(
                    limit = 1,
                    criteria = FileListSortCriteria.DATE_CREATED,
                    order = SortOrder.DESCENDING,
                )
        }

        assertContains(listed, "new.txt")
        assertFalse(listed.contains("old.txt"))
    }

    @Test
    fun `list matcher keeps files at or above minSize`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/keep.txt", "ok")
        writeRelative(workingDirectory, "catalog/tiny.txt", "x")
        val listed = listNames { matcher = LocalFileMatcher(minSize = 2) }

        assertContains(listed, "keep.txt")
        assertFalse(listed.contains("tiny.txt"))
    }

    @Test
    fun `list matcher keeps an exact path`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val keep = writeRelative(workingDirectory, "catalog/keep.txt", "keep")
        writeRelative(workingDirectory, "catalog/skip.txt", "skip")
        val listed = listNames { matcher = LocalFileMatcher(pathPattern = "catalog/keep.txt") }

        assertContains(listed, "keep.txt")
        assertFalse(listed.contains("skip.txt"))
    }

    @Test
    fun `list matcher excludes directories`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/keep.txt", "keep")
        Files.createDirectories(workingDirectory.resolve("catalog/folder"))
        val listed = listNames { matcher = LocalFileMatcher(directories = FileTypeRequirement.EXCLUDE) }

        assertContains(listed, "keep.txt")
        assertFalse(listed.lines().contains("folder"))
    }

    @Test
    fun `list matcher excludes regular files`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/keep.txt", "keep")
        Files.createDirectories(workingDirectory.resolve("catalog/folder"))
        val listed = listNames { matcher = LocalFileMatcher(regularFiles = FileTypeRequirement.EXCLUDE) }

        assertTrue(listed.lines().contains("folder"))
        assertFalse(listed.lines().contains("keep.txt"))
    }

    @Test
    fun `list matcher keeps files updated since a timestamp`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val old = writeRelative(workingDirectory, "catalog/old.txt", "old")
        writeRelative(workingDirectory, "catalog/recent.txt", "recent")
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")))
        val listed = listNames { matcher = LocalFileMatcher(updatedSince = Instant.parse("2024-01-01T00:00:00Z")) }

        assertContains(listed, "recent.txt")
        assertFalse(listed.contains("old.txt"))
    }

    @Test
    fun `list matcher keeps files updated in the last day`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val old = writeRelative(workingDirectory, "catalog/old.txt", "old")
        writeRelative(workingDirectory, "catalog/recent.txt", "recent")
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")))
        val listed = listNames { matcher = LocalFileMatcher(updatedInTheLast = Duration.ofDays(1)) }

        assertContains(listed, "recent.txt")
        assertFalse(listed.contains("old.txt"))
    }

    @Test
    fun `list matcher keeps files not updated in the last day`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val old = writeRelative(workingDirectory, "catalog/old.txt", "old")
        writeRelative(workingDirectory, "catalog/recent.txt", "recent")
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")))
        val listed = listNames { matcher = LocalFileMatcher(notUpdatedInTheLast = Duration.ofDays(1)) }

        assertContains(listed, "old.txt")
        assertFalse(listed.contains("recent.txt"))
    }

    @Test
    fun `list matcher keeps files created since a timestamp when birth time can be set`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val old = writeRelative(workingDirectory, "catalog/old.txt", "old")
        val recent = writeRelative(workingDirectory, "catalog/recent.txt", "recent")
        Assumptions.assumeTrue(assignCreationTime(old, Instant.parse("2020-01-01T00:00:00Z")) != null)
        Assumptions.assumeTrue(assignCreationTime(recent, Instant.now()) != null)
        val listed = listNames { matcher = LocalFileMatcher(createdSince = Instant.parse("2024-01-01T00:00:00Z")) }

        assertContains(listed, "recent.txt")
        assertFalse(listed.contains("old.txt"))
    }

    @Test
    fun `list matcher keeps files accessed since a timestamp when access time can be set`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val old = writeRelative(workingDirectory, "catalog/old.txt", "old")
        val recent = writeRelative(workingDirectory, "catalog/recent.txt", "recent")
        Assumptions.assumeTrue(assignAccessTime(old, Instant.parse("2020-01-01T00:00:00Z")) != null)
        Assumptions.assumeTrue(assignAccessTime(recent, Instant.parse("2024-06-01T00:00:00Z")) != null)
        val listed = listNames { matcher = LocalFileMatcher(accessedSince = Instant.parse("2024-01-01T00:00:00Z")) }

        assertContains(listed, "recent.txt")
        assertFalse(listed.contains("old.txt"))
    }

    @Test
    fun `list matcher excludes symbolic links`() = runBlocking {
        Assumptions.assumeTrue(supportsSymbolicLinks(workingDirectory))
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val real = writeRelative(workingDirectory, "catalog/real.txt", "real")
        Files.createSymbolicLink(workingDirectory.resolve("catalog/link.txt"), real.fileName)
        val listed = listNames { matcher = LocalFileMatcher(symbolicLinks = FileTypeRequirement.EXCLUDE) }

        assertContains(listed, "real.txt")
        assertFalse(listed.contains("link.txt"))
    }

    @Test
    fun `list target keeps the listener payload and stores the listing`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "catalog/keep.txt", "keep")
        val payload = workingDirectory.resolve("output/payload.txt")
        val listing = workingDirectory.resolve("output/list.txt")
        val flow =
            flow("list-target") {
                pollingFileListener()
                fileList(fileConnection(FILE_CONNECTION_NAME)) {
                    directoryPath = "catalog"
                    targetConfig = TargetConfig("listed")
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/payload.txt"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/list.txt"
                    content = """#[(vars.listed map $.attributes.fileName) joinBy "\n"]"""
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { payload.exists() && listing.exists() }
            assertEquals("trigger", payload.readText())
            assertContains(listing.readText(), "keep.txt")
        }
    }

    private suspend fun listNames(
        recursive: Boolean = false,
        configure: FileListBuilder.() -> Unit,
    ): String {
        val output = workingDirectory.resolve("output/list.txt")
        val flow =
            flow("list-selection") {
                pollingFileListener()
                fileList(fileConnection(FILE_CONNECTION_NAME)) {
                    directoryPath = "catalog"
                    this.recursive = recursive
                    configure()
                }
                writeListedNames("output/list.txt")
            }
        var listed = ""
        runFileApplication(workingDirectory, flow) {
            listed = awaitListed(output)
        }
        return listed
    }
}

/** Wait for a non-empty listing; [Path.exists] alone can see the file before bytes are written. */
private suspend fun awaitListed(output: Path): String {
    var listed = ""
    awaitTrue {
        if (!output.exists()) return@awaitTrue false
        listed = output.readText()
        listed.isNotEmpty()
    }
    return listed
}

private fun dev.museframework.dsl.api.dsl.PipelineOwner.writeListedNames(path: String) {
    setPayload {
        value = """#[(payload map $.attributes.fileName) joinBy "\n"]"""
    }
    fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
        this.path = path
    }
}

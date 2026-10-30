package dev.museframework.connectors.file

import dev.museframework.dsl.dsl.flow
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileCopy
import dev.museframework.file.api.dsl.fileCreateDirectory
import dev.museframework.file.api.dsl.fileDelete
import dev.museframework.file.api.dsl.fileMove
import dev.museframework.file.api.dsl.fileRename
import dev.museframework.file.api.dsl.fileWrite
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileMutationIT {
    @TempDir
    lateinit var workingDirectory: Path

    @Test
    fun `copy writes the source into the target directory and keeps the original`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "data/tree/child.txt", "child-body")
        val source = workingDirectory.resolve("data/tree/child.txt")
        val copied = workingDirectory.resolve("copies/tree/child.txt")
        val flow =
            flow("copy-directory") {
                pollingFileListener()
                fileCopy(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/tree"
                    targetPath = "copies"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { copied.exists() }
            assertEquals("child-body", copied.readText())
            assertEquals("child-body", source.readText())
        }
    }

    @Test
    fun `copy renames into the target directory`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "data/source.txt", "source-body")
        val copied = workingDirectory.resolve("copies/renamed.txt")
        val flow =
            flow("copy-rename") {
                pollingFileListener()
                fileCopy(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/source.txt"
                    targetPath = "copies"
                    renameTo = "renamed.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { copied.exists() }
            assertEquals("source-body", copied.readText())
            assertTrue(workingDirectory.resolve("data/source.txt").exists())
        }
    }

    @Test
    fun `copy refuses an existing destination until overwrite is set`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        writeRelative(workingDirectory, "data/source.txt", "source-body")
        writeRelative(workingDirectory, "copies/source.txt", "existing")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("copy-refuses-existing") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/before.txt"
                }
                fileCopy(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/source.txt"
                    targetPath = "copies"
                    overwrite = false
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { before.exists() && before.readText() == "payload" }
            assertFalse(after.exists())
            assertEquals("existing", workingDirectory.resolve("copies/source.txt").readText())
            assertEquals("source-body", workingDirectory.resolve("data/source.txt").readText())
            assertTrue(trigger.exists())
        }
    }

    @Test
    fun `copy replaces an existing destination when overwrite is set`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "data/source.txt", "source-body")
        writeRelative(workingDirectory, "copies/source.txt", "existing")
        val copied = workingDirectory.resolve("copies/source.txt")
        val flow =
            flow("copy-overwrite") {
                pollingFileListener()
                fileCopy(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/source.txt"
                    targetPath = "copies"
                    overwrite = true
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { copied.readText() == "source-body" }
            assertTrue(workingDirectory.resolve("data/source.txt").exists())
        }
    }

    @Test
    fun `move removes the source and keeps nested directory contents`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "data/tree/child.txt", "child-body")
        val source = workingDirectory.resolve("data/tree")
        val moved = workingDirectory.resolve("moved/tree/child.txt")
        val flow =
            flow("move-directory") {
                pollingFileListener()
                fileMove(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/tree"
                    targetPath = "moved"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { moved.exists() && !source.exists() }
            assertEquals("child-body", moved.readText())
        }
    }

    @Test
    fun `move renames into a new parent directory`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "data/file.txt", "move-body")
        val source = workingDirectory.resolve("data/file.txt")
        val moved = workingDirectory.resolve("dest/renamed.txt")
        val flow =
            flow("move-rename") {
                pollingFileListener()
                fileMove(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/file.txt"
                    targetPath = "dest"
                    renameTo = "renamed.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { moved.exists() && !source.exists() }
            assertEquals("move-body", moved.readText())
        }
    }

    @Test
    fun `move fails when the target directory is missing and createParentDirectories is false`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        writeRelative(workingDirectory, "data/file.txt", "stay")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("move-missing-parent") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/before.txt"
                }
                fileMove(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/file.txt"
                    targetPath = "missing"
                    createParentDirectories = false
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { before.exists() && before.readText() == "payload" }
            assertFalse(after.exists())
            assertEquals("stay", workingDirectory.resolve("data/file.txt").readText())
            assertFalse(workingDirectory.resolve("missing").exists())
            assertTrue(trigger.exists())
        }
    }

    @Test
    fun `delete removes a file and a directory tree`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val file = writeRelative(workingDirectory, "data/gone.txt", "gone")
        val tree = writeRelative(workingDirectory, "data/tree/child.txt", "child")
        val flow =
            flow("delete-paths") {
                pollingFileListener()
                fileDelete(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "data/gone.txt"
                }
                fileDelete(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "data/tree"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { !file.exists() && !tree.parent.exists() }
            assertFalse(file.exists())
            assertFalse(workingDirectory.resolve("data/tree").exists())
        }
    }

    @Test
    fun `rename changes the file name in the same directory`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val source = writeRelative(workingDirectory, "data/old.txt", "rename-body")
        val renamed = workingDirectory.resolve("data/renamed.txt")
        val flow =
            flow("rename-file") {
                pollingFileListener()
                fileRename(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "data/old.txt"
                    to = "renamed.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { renamed.exists() && !source.exists() }
            assertEquals("rename-body", renamed.readText())
        }
    }

    @Test
    fun `rename refuses an existing name until overwrite is set`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        writeRelative(workingDirectory, "data/old.txt", "from-old")
        writeRelative(workingDirectory, "data/taken.txt", "from-taken")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("rename-refuses-existing") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/before.txt"
                }
                fileRename(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "data/old.txt"
                    to = "taken.txt"
                    overwrite = false
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { before.exists() && before.readText() == "payload" }
            assertFalse(after.exists())
            assertEquals("from-old", workingDirectory.resolve("data/old.txt").readText())
            assertEquals("from-taken", workingDirectory.resolve("data/taken.txt").readText())
            assertTrue(trigger.exists())
        }
    }

    @Test
    fun `rename replaces an existing name when overwrite is set`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val source = writeRelative(workingDirectory, "data/old.txt", "from-old")
        val taken = writeRelative(workingDirectory, "data/taken.txt", "from-taken")
        val flow =
            flow("rename-overwrite") {
                pollingFileListener()
                fileRename(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "data/old.txt"
                    to = "taken.txt"
                    overwrite = true
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { !source.exists() && taken.readText() == "from-old" }
            assertEquals("from-old", taken.readText())
        }
    }

    @Test
    fun `create directory makes a missing path`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val created = workingDirectory.resolve("created/nested")
        val flow =
            flow("create-directory") {
                pollingFileListener()
                fileCreateDirectory(fileConnection(FILE_CONNECTION_NAME)) {
                    directoryPath = "created/nested"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { Files.isDirectory(created) }
            assertTrue(Files.isDirectory(created))
        }
    }

    @Test
    fun `create directory refuses an existing path and stops the flow`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        writeRelative(workingDirectory, "created/placeholder.txt", "keep")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("create-directory-exists") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/before.txt"
                }
                fileCreateDirectory(fileConnection(FILE_CONNECTION_NAME)) {
                    directoryPath = "created"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { before.exists() && before.readText() == "payload" }
            assertFalse(after.exists())
            assertEquals("keep", workingDirectory.resolve("created/placeholder.txt").readText())
            assertTrue(trigger.exists())
        }
    }

    @Test
    fun `move replaces an existing destination when overwrite is set`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val source = writeRelative(workingDirectory, "data/file.txt", "fresh")
        val destination = writeRelative(workingDirectory, "dest/file.txt", "stale")
        val flow =
            flow("move-overwrite") {
                pollingFileListener()
                fileMove(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/file.txt"
                    targetPath = "dest"
                    overwrite = true
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { destination.exists() && !source.exists() }
            assertEquals("fresh", destination.readText())
        }
    }

    @Test
    fun `move leaves an existing destination unchanged until overwrite is set`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        writeRelative(workingDirectory, "data/file.txt", "fresh")
        writeRelative(workingDirectory, "dest/file.txt", "stale")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("move-overwrite-refused") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/before.txt"
                }
                fileMove(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/file.txt"
                    targetPath = "dest"
                    overwrite = false
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { before.exists() && before.readText() == "payload" }
            assertFalse(after.exists())
            assertEquals("fresh", workingDirectory.resolve("data/file.txt").readText())
            assertEquals("stale", workingDirectory.resolve("dest/file.txt").readText())
            assertTrue(trigger.exists())
        }
    }

    @Test
    fun `copy fails when the target directory is missing and createParentDirectories is false`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        writeRelative(workingDirectory, "data/file.txt", "stay")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("copy-missing-parent") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/before.txt"
                }
                fileCopy(fileConnection(FILE_CONNECTION_NAME)) {
                    sourcePath = "data/file.txt"
                    targetPath = "missing"
                    createParentDirectories = false
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { before.exists() && before.readText() == "payload" }
            assertFalse(after.exists())
            assertEquals("stay", workingDirectory.resolve("data/file.txt").readText())
            assertFalse(workingDirectory.resolve("missing").exists())
            assertTrue(trigger.exists())
        }
    }
}

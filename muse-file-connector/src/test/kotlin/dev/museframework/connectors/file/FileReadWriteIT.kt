package dev.museframework.connectors.file

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileRead
import dev.museframework.file.api.dsl.fileWrite
import dev.museframework.file.api.model.FileWriteMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileReadWriteIT {
    @TempDir
    lateinit var workingDirectory: Path

    @Test
    fun `read streams another file through the engine into a write`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "side/data.txt", "read-body")
        val flow =
            flow("read-through-engine") {
                pollingFileListener()
                fileRead(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "side/data.txt"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/read.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            val output = workingDirectory.resolve("output/read.txt")
            awaitTrue { output.exists() && output.readText() == "read-body" }
            assertEquals("read-body", output.readText())
        }
    }

    @Test
    fun `write appends the listener payload to an existing file`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "tail")
        writeRelative(workingDirectory, "output/existing.txt", "head-")
        val flow =
            flow("append-through-engine") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/existing.txt"
                    mode = FileWriteMode.APPEND
                }
            }

        runFileApplication(workingDirectory, flow) {
            val output = workingDirectory.resolve("output/existing.txt")
            awaitTrue { output.readText() == "head-tail" }
            assertEquals("head-tail", output.readText())
        }
    }

    @Test
    fun `write overwrites an existing file`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "new")
        writeRelative(workingDirectory, "output/existing.txt", "old")
        val flow =
            flow("overwrite-through-engine") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/existing.txt"
                    mode = FileWriteMode.OVERWRITE
                }
            }

        runFileApplication(workingDirectory, flow) {
            val output = workingDirectory.resolve("output/existing.txt")
            awaitTrue { output.readText() == "new" }
            assertEquals("new", output.readText())
        }
    }

    @Test
    fun `create new writes a missing file and creates its parent`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "created")
        val output = workingDirectory.resolve("output/nested/created.txt")
        val flow =
            flow("create-new-through-engine") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/nested/created.txt"
                    mode = FileWriteMode.CREATE_NEW
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { output.exists() && output.readText() == "created" }
            assertEquals("created", output.readText())
            assertTrue(output.parent.exists())
        }
    }

    @Test
    fun `create new leaves an existing file unchanged and stops the flow`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        writeRelative(workingDirectory, "output/existing.txt", "original")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val existing = workingDirectory.resolve("output/existing.txt")
        val flow =
            flow("create-new-conflict") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/before.txt"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/existing.txt"
                    mode = FileWriteMode.CREATE_NEW
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { before.exists() && before.readText() == "payload" }
            assertFalse(after.exists())
            assertEquals("original", existing.readText())
            assertTrue(trigger.exists())
        }
    }

    @Test
    fun `write without a parent directory fails and stops the flow`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val missing = workingDirectory.resolve("nested/out.txt")
        val flow =
            flow("missing-parent") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/before.txt"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "nested/out.txt"
                    createParentDirectories = false
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { before.exists() && before.readText() == "payload" }
            assertFalse(after.exists())
            assertFalse(missing.exists())
            assertTrue(trigger.exists())
        }
    }

    @Test
    fun `locked read streams file bytes through the engine`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "side/locked.txt", "locked-body")
        val flow =
            flow("locked-read") {
                pollingFileListener()
                fileRead(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "side/locked.txt"
                    lock = true
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/locked.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            val output = workingDirectory.resolve("output/locked.txt")
            awaitTrue { output.exists() && output.readText() == "locked-body" }
            assertEquals("locked-body", output.readText())
        }
    }

    @Test
    fun `write uses the content expression instead of the listener payload`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val flow =
            flow("content-expression") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/written.txt"
                    content = "from-expression"
                }
            }

        runFileApplication(workingDirectory, flow) {
            val output = workingDirectory.resolve("output/written.txt")
            awaitTrue { output.exists() && output.readText() == "from-expression" }
            assertEquals("from-expression", output.readText())
        }
    }

    @Test
    fun `locked write stores the listener payload`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val flow =
            flow("locked-write") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/locked.txt"
                    lock = true
                }
            }

        runFileApplication(workingDirectory, flow) {
            val output = workingDirectory.resolve("output/locked.txt")
            awaitTrue { output.exists() && output.readText() == "trigger" }
            assertEquals("trigger", output.readText())
        }
    }

    @Test
    fun `listener attributes are written from the event`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val flow =
            flow("listener-attributes") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/name.txt"
                    content = "#[attributes.fileName]"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/size.txt"
                    content = "#[attributes.size as String]"
                }
            }

        runFileApplication(workingDirectory, flow) {
            val name = workingDirectory.resolve("output/name.txt")
            val size = workingDirectory.resolve("output/size.txt")
            awaitTrue {
                name.exists() && size.exists() && name.readText() == "input.txt" && size.readText() == "7"
            }
            assertEquals("input.txt", name.readText())
            assertEquals("7", size.readText())
        }
    }

    @Test
    fun `read target keeps the listener payload and stores the file bytes`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "side/data.txt", "read-body")
        val flow =
            flow("read-target") {
                pollingFileListener()
                fileRead(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "side/data.txt"
                    targetConfig = TargetConfig("side")
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/trigger.txt"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/side.txt"
                    content = "#[vars.side]"
                }
            }

        runFileApplication(workingDirectory, flow) {
            val trigger = workingDirectory.resolve("output/trigger.txt")
            val side = workingDirectory.resolve("output/side.txt")
            awaitTrue {
                trigger.exists() && side.exists() && trigger.readText() == "trigger" && side.readText() == "read-body"
            }
            assertEquals("trigger", trigger.readText())
            assertEquals("read-body", side.readText())
        }
    }
}

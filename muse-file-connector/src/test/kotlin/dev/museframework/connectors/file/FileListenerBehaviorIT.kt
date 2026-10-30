package dev.museframework.connectors.file

import dev.museframework.dsl.dsl.flow
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileRead
import dev.museframework.file.api.dsl.fileWrite
import dev.museframework.file.api.model.FileWriteMode
import dev.museframework.file.api.model.LocalFileMatcher
import dev.museframework.file.api.model.WatermarkMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.appendText
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileListenerBehaviorIT {
    @TempDir
    lateinit var workingDirectory: Path

    @Test
    fun `listener moves and renames the file after the flow writes its bytes`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "archived-body")
        val output = workingDirectory.resolve("output/copied.txt")
        val archived = workingDirectory.resolve("archive/processed.txt")
        val flow =
            flow("listener-move") {
                pollingFileListener {
                    moveToDirectory = "archive"
                    renameTo = "processed.txt"
                    overwrite = true
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/copied.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { output.exists() && !trigger.exists() && archived.exists() }
            assertEquals("archived-body", output.readText())
            assertEquals("archived-body", archived.readText())
        }
    }

    @Test
    fun `listener overwrite replaces an archived file`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "replacement")
        writeRelative(workingDirectory, "archive/processed.txt", "previous")
        val archived = workingDirectory.resolve("archive/processed.txt")
        val flow =
            flow("listener-overwrite") {
                pollingFileListener {
                    moveToDirectory = "archive"
                    renameTo = "processed.txt"
                    overwrite = true
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/copied.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { !trigger.exists() && archived.readText() == "replacement" }
            assertEquals("replacement", archived.readText())
        }
    }

    @Test
    fun `failed flow keeps the trigger when post action on failure is disabled`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        writeRelative(workingDirectory, "output/existing.txt", "original")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("post-action-skipped") {
                pollingFileListener {
                    autoDelete = true
                }
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
            assertEquals("original", workingDirectory.resolve("output/existing.txt").readText())
            assertTrue(trigger.exists())
        }
    }

    @Test
    fun `failed flow deletes the trigger when post action on failure is enabled`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "payload")
        writeRelative(workingDirectory, "output/existing.txt", "original")
        val before = workingDirectory.resolve("output/before.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("post-action-on-failure") {
                pollingFileListener {
                    autoDelete = true
                    applyPostActionWhenFailed = true
                }
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
            awaitTrue { before.exists() && before.readText() == "payload" && !trigger.exists() }
            assertFalse(after.exists())
            assertEquals("original", workingDirectory.resolve("output/existing.txt").readText())
        }
    }

    @Test
    fun `listener matcher dispatches only matching names`() = runBlocking {
        val keep = writeRelative(workingDirectory, "incoming/keep.txt", "keep-body")
        val skip = writeRelative(workingDirectory, "incoming/skip.dat", "skip-body")
        val output = workingDirectory.resolve("output/copied.txt")
        val flow =
            flow("listener-matcher") {
                pollingFileListener {
                    matcher = LocalFileMatcher(filenamePattern = "*.txt")
                    autoDelete = true
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/copied.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { !keep.exists() && output.exists() && output.readText() == "keep-body" }
            delay(300)
            assertTrue(skip.exists())
            assertEquals("skip-body", skip.readText())
            assertEquals("keep-body", output.readText())
        }
    }

    @Test
    fun `listener matcher archives matching csv files into moveToDirectory`() = runBlocking {
        val keep = writeRelative(workingDirectory, "input/data.csv", "id,name\n1,a")
        val skip = writeRelative(workingDirectory, "input/notes.txt", "skip-me")
        val processed = workingDirectory.resolve("processed/data.csv")
        val archived = workingDirectory.resolve("output/data.csv")
        val flow =
            flow("listener-archive-matcher") {
                pollingFileListener(directory = "input") {
                    matcher = LocalFileMatcher(filenamePattern = "*.csv")
                    moveToDirectory = "output"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "processed/data.csv"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { !keep.exists() && processed.exists() && archived.exists() }
            delay(300)
            assertTrue(skip.exists())
            assertEquals("skip-me", skip.readText())
            assertEquals("id,name\n1,a", processed.readText())
            assertEquals("id,name\n1,a", archived.readText())
        }
    }

    @Test
    fun `watermark skips an older file and still accepts an equal timestamp`() = runBlocking {
        val stamp = Instant.parse("2024-06-01T00:00:00Z")
        val newer = writeRelative(workingDirectory, "incoming/new.txt", "new-body")
        Files.setLastModifiedTime(newer, FileTime.from(stamp))
        val observed = Files.getLastModifiedTime(newer)
        val output = workingDirectory.resolve("output/copied.txt")
        val flow =
            flow("listener-watermark") {
                pollingFileListener {
                    watermarkMode = WatermarkMode.MODIFIED_TIMESTAMP
                    autoDelete = true
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/copied.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { !newer.exists() && output.exists() && output.readText() == "new-body" }
            val stagedOlder = writeRelative(workingDirectory, "staging/old.txt", "old-body")
            Files.setLastModifiedTime(stagedOlder, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")))
            val older = Files.move(stagedOlder, workingDirectory.resolve("incoming/old.txt"))
            delay(300)
            assertTrue(older.exists())
            assertEquals("old-body", older.readText())
            val stagedSame = writeRelative(workingDirectory, "staging/same.txt", "same-body")
            Files.setLastModifiedTime(stagedSame, observed)
            val same = Files.move(stagedSame, workingDirectory.resolve("incoming/same.txt"))
            awaitTrue { !same.exists() && output.readText() == "same-body" }
            assertTrue(older.exists())
            assertEquals("old-body", older.readText())
        }
    }

    @Test
    fun `recursive listener dispatches a nested file`() = runBlocking {
        val nested = writeRelative(workingDirectory, "incoming/nested/child.txt", "nested-body")
        val output = workingDirectory.resolve("output/copied.txt")
        val flow =
            flow("listener-recursive") {
                pollingFileListener(recursive = true) {
                    autoDelete = true
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/copied.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { !nested.exists() && output.exists() && output.readText() == "nested-body" }
            assertEquals("nested-body", output.readText())
        }
    }

    @Test
    fun `flat listener ignores a nested file`() = runBlocking {
        val root = writeRelative(workingDirectory, "incoming/root.txt", "root-body")
        val nested = writeRelative(workingDirectory, "incoming/nested/child.txt", "nested-body")
        val output = workingDirectory.resolve("output/copied.txt")
        val flow =
            flow("listener-flat") {
                pollingFileListener(recursive = false) {
                    autoDelete = true
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/copied.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { !root.exists() && output.exists() && output.readText() == "root-body" }
            delay(300)
            assertTrue(nested.exists())
            assertEquals("nested-body", nested.readText())
            assertEquals("root-body", output.readText())
        }
    }

    @Test
    fun `read fails when the file changes size during the stability check`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/go.txt", "go")
        val growing = writeRelative(workingDirectory, "data/growing.txt", "base")
        val done = workingDirectory.resolve("output/done.txt")
        val mutating = AtomicBoolean(true)
        val mutator =
            Thread {
                while (mutating.get()) {
                    growing.appendText("x")
                    Thread.sleep(15)
                }
            }
        mutator.isDaemon = true
        mutator.start()
        try {
            val flow =
                flow("unstable-size") {
                    pollingFileListener()
                    fileRead(fileConnection(FILE_CONNECTION_NAME)) {
                        path = "data/growing.txt"
                        timeBetweenSizeCheck = 300
                        timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS
                    }
                    fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                        path = "output/copied.txt"
                    }
                    fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                        path = "output/done.txt"
                    }
                }
            runFileApplication(workingDirectory, flow) {
                delay(1_000)
                assertFalse(done.exists())
                assertTrue(trigger.exists())
            }
        } finally {
            mutating.set(false)
            mutator.join(1_000)
        }
    }

    @Test
    fun `created watermark skips an older birth time and still accepts an equal one`() = runBlocking {
        val stamp = Instant.parse("2024-06-01T00:00:00Z")
        val newer = writeRelative(workingDirectory, "incoming/new.txt", "new-body")
        val observed = assignCreationTime(newer, stamp)
        Assumptions.assumeTrue(observed != null)
        val output = workingDirectory.resolve("output/copied.txt")
        val flow =
            flow("listener-created-watermark") {
                pollingFileListener {
                    watermarkMode = WatermarkMode.CREATED_TIMESTAMP
                    autoDelete = true
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/copied.txt"
                }
            }

        runFileApplication(workingDirectory, flow) {
            awaitTrue { !newer.exists() && output.exists() && output.readText() == "new-body" }
            val stagedOlder = writeRelative(workingDirectory, "staging/old.txt", "old-body")
            assertTrue(assignCreationTime(stagedOlder, Instant.parse("2020-01-01T00:00:00Z")) != null)
            val older = Files.move(stagedOlder, workingDirectory.resolve("incoming/old.txt"))
            delay(300)
            assertTrue(older.exists())
            assertEquals("old-body", older.readText())
            val stagedSame = writeRelative(workingDirectory, "staging/same.txt", "same-body")
            assertTrue(assignCreationTime(stagedSame, observed!!) != null)
            val same = Files.move(stagedSame, workingDirectory.resolve("incoming/same.txt"))
            awaitTrue { !same.exists() && output.readText() == "same-body" }
            assertTrue(older.exists())
        }
    }

    @Test
    fun `listener skips a file whose size changes during its stability check`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "base")
        val done = workingDirectory.resolve("output/done.txt")
        val copied = workingDirectory.resolve("output/copied.txt")
        val mutating = AtomicBoolean(true)
        val mutator =
            Thread {
                while (mutating.get()) {
                    trigger.appendText("x")
                    Thread.sleep(15)
                }
            }
        mutator.isDaemon = true
        mutator.start()
        try {
            val flow =
                flow("listener-unstable-size") {
                    pollingFileListener {
                        timeBetweenSizeCheck = 300
                        timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS
                        autoDelete = true
                    }
                    fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                        path = "output/copied.txt"
                    }
                    fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                        path = "output/done.txt"
                    }
                }
            runFileApplication(workingDirectory, flow) {
                delay(1_000)
                // Stop the mutator before asserting existence. appendText recreates a deleted
                // path, which previously masked send-then-autoDelete of a still-growing file.
                mutating.set(false)
                mutator.join(1_000)
                assertFalse(done.exists(), "growing file must not complete the flow")
                assertFalse(copied.exists(), "growing file must not be delivered to the flow")
                assertTrue(
                    trigger.exists(),
                    "growing file must remain on disk; must not be dispatched then auto-deleted",
                )
            }
        } finally {
            mutating.set(false)
            mutator.join(1_000)
        }
    }

    @Test
    fun `read uses the connection size check when the operation does not set one`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/go.txt", "go")
        val growing = writeRelative(workingDirectory, "data/growing.txt", "base")
        val done = workingDirectory.resolve("output/done.txt")
        val mutating = AtomicBoolean(true)
        val mutator =
            Thread {
                while (mutating.get()) {
                    growing.appendText("x")
                    Thread.sleep(15)
                }
            }
        mutator.isDaemon = true
        mutator.start()
        try {
            val connection =
                fileConnection {
                    name = FILE_CONNECTION_NAME
                    workingDirectory = this@FileListenerBehaviorIT.workingDirectory.toString()
                    timeBetweenSizeCheck = 300
                    timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS
                }
            val flow =
                flow("connection-size-check") {
                    pollingFileListener()
                    fileRead(fileConnection(FILE_CONNECTION_NAME)) {
                        path = "data/growing.txt"
                    }
                    fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                        path = "output/copied.txt"
                    }
                    fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                        path = "output/done.txt"
                    }
                }
            runFileApplication(workingDirectory, flow, connection) {
                delay(1_000)
                assertFalse(done.exists())
                assertTrue(trigger.exists())
            }
        } finally {
            mutating.set(false)
            mutator.join(1_000)
        }
    }
}

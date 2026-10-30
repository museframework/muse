package dev.museframework.connectors.file

import dev.museframework.connectors.file.internal.FileConnectorException
import dev.museframework.dsl.builder.FlowBuilder
import dev.museframework.dsl.dsl.flow
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileRead
import dev.museframework.file.api.dsl.fileWrite
import dev.museframework.file.api.dsl.fileList
import dev.museframework.file.api.model.FileErrorType
import dev.museframework.file.api.model.FileWriteMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.ServiceLoader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.appendText
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileErrorTypeIT {
    @TempDir
    lateinit var workingDirectory: Path

    @Test
    fun `missing relative path fails the flow with ILLEGAL_PATH`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("missing-path") {
                pollingFileListener()
                fileRead(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "missing.txt"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
                recordFileError()
            }

        runFileApplication(workingDirectory, flow) {
            awaitError("FILE:ILLEGAL_PATH")
            assertFalse(after.exists())
        }
    }

    @Test
    fun `FILE_DOESNT_EXIST handler does not recover a missing relative path`() = runBlocking {
        val trigger = writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val marker = workingDirectory.resolve("output/error.txt")
        val after = workingDirectory.resolve("output/after.txt")
        val flow =
            flow("missing-path-wrong-handler") {
                pollingFileListener()
                fileRead(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "missing.txt"
                }
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/after.txt"
                }
                errorHandler {
                    onErrorContinue(FileErrorType.FILE_DOESNT_EXIST) {
                        fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                            path = "output/error.txt"
                            content = "FILE_DOESNT_EXIST"
                        }
                    }
                }
            }

        runFileApplication(workingDirectory, flow) {
            delay(400)
            assertFalse(marker.exists())
            assertFalse(after.exists())
            assertTrue(trigger.exists())
        }
    }

    @Test
    fun `listing a regular file fails the flow with ILLEGAL_PATH`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "data/file.txt", "body")
        val flow =
            flow("list-file") {
                pollingFileListener()
                fileList(fileConnection(FILE_CONNECTION_NAME)) {
                    directoryPath = "data/file.txt"
                }
                recordFileError()
            }

        runFileApplication(workingDirectory, flow) {
            awaitError("FILE:ILLEGAL_PATH")
        }
    }

    @Test
    fun `create new on an existing file fails the flow with FILE_ALREADY_EXISTS`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        writeRelative(workingDirectory, "output/existing.txt", "original")
        val flow =
            flow("create-new-exists") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/existing.txt"
                    mode = FileWriteMode.CREATE_NEW
                }
                recordFileError()
            }

        runFileApplication(workingDirectory, flow) {
            awaitError("FILE:FILE_ALREADY_EXISTS")
            assertEquals("original", workingDirectory.resolve("output/existing.txt").readText())
        }
    }

    @Test
    fun `null write content fails the flow with ILLEGAL_CONTENT`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val flow =
            flow("null-content") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/empty.txt"
                    content = "#[null]"
                }
                recordFileError()
            }

        runFileApplication(workingDirectory, flow) {
            awaitError("FILE:ILLEGAL_CONTENT")
            assertFalse(workingDirectory.resolve("output/empty.txt").exists())
        }
    }

    @Test
    fun `locked write fails the flow with FILE_LOCK when the file is already locked`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val locked = writeRelative(workingDirectory, "output/locked.txt", "original")
        FileChannel.open(locked, StandardOpenOption.WRITE).use { channel ->
            val held = checkNotNull(channel.tryLock())
            try {
                val after = workingDirectory.resolve("output/after.txt")
                val flow =
                    flow("overlapping-lock") {
                        pollingFileListener()
                        fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                            path = "output/locked.txt"
                            lock = true
                        }
                        fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                            path = "output/after.txt"
                        }
                        recordFileError()
                    }
                runFileApplication(workingDirectory, flow) {
                    awaitError("FILE:FILE_LOCK")
                    assertFalse(after.exists())
                }
            } finally {
                held.release()
            }
        }
    }

    @Test
    fun `unreadable file fails the flow with ACCESS_DENIED`() = runBlocking {
        writeRelative(workingDirectory, "incoming/input.txt", "trigger")
        val secret = writeRelative(workingDirectory, "data/secret.txt", "hidden")
        val permissionsChanged =
            runCatching { Files.setPosixFilePermissions(secret, emptySet<PosixFilePermission>()) }.isSuccess
        Assumptions.assumeTrue(permissionsChanged)
        val denied =
            runCatching { Files.readAttributes(secret, BasicFileAttributes::class.java) }.exceptionOrNull()
        Assumptions.assumeTrue(denied is java.nio.file.AccessDeniedException)
        val flow =
            flow("access-denied") {
                pollingFileListener()
                fileRead(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "data/secret.txt"
                }
                recordFileError()
            }

        try {
            runFileApplication(workingDirectory, flow) {
                awaitError("FILE:ACCESS_DENIED")
            }
        } finally {
            secret.toFile().setReadable(true)
        }
    }

    @Test
    fun `size change during a read fails the flow with FILE ANY`() = runBlocking {
        writeRelative(workingDirectory, "incoming/go.txt", "go")
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
                flow("unstable-size-type") {
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
                    recordFileError()
                }
            runFileApplication(workingDirectory, flow) {
                awaitError("FILE:ANY")
                assertFalse(done.exists())
            }
        } finally {
            mutating.set(false)
            mutator.join(1_000)
        }
    }

    @Test
    fun `missing working directory fails bootstrap with FILE_DOESNT_EXIST`() = runBlocking {
        val failure = expectFailure { initFileApplication(workingDirectory.resolve("missing-root")) }
        assertEquals(FileErrorType.FILE_DOESNT_EXIST, fileFailure(failure).errorType)
    }

    @Test
    fun `working directory that is a file fails bootstrap with FILE_IS_NOT_DIRECTORY`() = runBlocking {
        val file = workingDirectory.resolve("not-a-directory.txt").also { it.writeText("x") }
        val failure = expectFailure { initFileApplication(file) }
        assertEquals(FileErrorType.FILE_IS_NOT_DIRECTORY, fileFailure(failure).errorType)
    }

    private suspend fun expectFailure(body: suspend () -> Unit): RuntimeException =
        try {
            body()
            throw AssertionError("Expected the file connector bootstrap to fail")
        } catch (failure: RuntimeException) {
            failure
        }

    private suspend fun awaitError(expected: String) {
        val error = workingDirectory.resolve("output/error.txt")
        awaitTrue { error.exists() }
        assertEquals(expected, error.readText())
    }

    private suspend fun initFileApplication(directory: Path) {
        val connection =
            fileConnection {
                name = FILE_CONNECTION_NAME
                workingDirectory = directory.toString()
            }
        val fileFlow =
            flow("bootstrap") {
                pollingFileListener()
                fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                    path = "output/out.txt"
                }
            }
        val factory = MuseComponentFactory()
        ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { it.register(factory) }
        val application =
            bootstrap(
                MuseApplicationConfig(
                    flows = listOf(fileFlow),
                    configs = listOf(connection),
                    factory = factory,
                ),
            )
        try {
            application.init()
        } finally {
            runCatching { application.stop() }
        }
    }

    private fun fileFailure(failure: Throwable): FileConnectorException =
        generateSequence(failure) { it.cause }.filterIsInstance<FileConnectorException>().first()
}

private fun FlowBuilder.recordFileError() {
    errorHandler {
        onErrorContinue(FileErrorType.ANY) {
            fileWrite(fileConnection(FILE_CONNECTION_NAME)) {
                path = "output/error.txt"
                content = "#[error.errorType.namespace ++ ':' ++ error.errorType.identifier]"
            }
        }
    }
}

package dev.museframework.connectors.file.source

import dev.museframework.connectors.file.internal.LocalFileSystem
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.file.api.config.FileConnectionConfig
import dev.museframework.file.api.config.FileListenerConfig
import dev.museframework.file.api.model.FileErrorType
import dev.museframework.file.api.model.LocalFileAttributes
import dev.museframework.file.api.model.WatermarkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileListenerMuseSourceTest {
    @TempDir
    lateinit var workingDirectory: Path

    private val evaluator =
        object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any> =
                TypedValue.of(expression as Any)
        }

    private fun source(
        autoDelete: Boolean = false,
        moveToDirectory: String? = null,
        renameTo: String? = null,
        watermarkMode: WatermarkMode = WatermarkMode.DISABLED,
        startDelayMillis: Long = 0,
        listRegularFiles: ((LocalFileSystem, String) -> List<LocalFileAttributes>)? = null,
    ): FileListenerMuseSource {
        val connection = FileConnectionConfig(workingDirectory = workingDirectory.toString())
        val fileSystem = LocalFileSystem(connection)
        val config =
            FileListenerConfig(
                connectionConfig = ConfigRef.ByReference(connection),
                directory = "incoming",
                pollingFrequencyMillis = 20,
                startDelayMillis = startDelayMillis,
                autoDelete = autoDelete,
                moveToDirectory = moveToDirectory,
                renameTo = renameTo,
                watermarkMode = watermarkMode,
            )
        return FileListenerMuseSource(
            config = config,
            connectionConfig = connection,
            expressionEvaluator = evaluator,
            fileSystem = fileSystem,
            listRegularFiles =
                listRegularFiles?.let { listing ->
                    { directory -> listing(fileSystem, directory) }
                },
        )
    }

    @Test
    fun `watermark dispatches an older file visited after a newer one`() = runBlocking(Dispatchers.Default) {
        val incoming = workingDirectory.resolve("incoming")
        java.nio.file.Files.createDirectories(incoming)
        val older = incoming.resolve("older.txt").also { it.writeText("old") }
        val newer = incoming.resolve("newer.txt").also { it.writeText("new") }
        val olderStamp = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"))
        val newerStamp = FileTime.from(Instant.parse("2024-06-01T00:00:00Z"))
        java.nio.file.Files.setLastModifiedTime(older, olderStamp)
        java.nio.file.Files.setLastModifiedTime(newer, newerStamp)
        val source =
            source(watermarkMode = WatermarkMode.MODIFIED_TIMESTAMP) { fileSystem, directory ->
                fileSystem.listAttributes(directory, recursive = true, matcher = null, subset = null)
                    .filter { it.regularFile }
                    .sortedByDescending { it.lastModifiedTime }
            }
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        val names = mutableSetOf<String>()
        repeat(2) {
            val envelope = withTimeout(2_000) { channel.receive() }
            names += assertIs<LocalFileAttributes>(envelope.event.attributes!!.value).fileName
            envelope.event.payload!!.asCursorStreamProvider()!!.releaseResources()
        }
        assertEquals(setOf("older.txt", "newer.txt"), names)

        source.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `failed older file stays eligible after a newer file succeeds`() = runBlocking(Dispatchers.Default) {
        val incoming = workingDirectory.resolve("incoming")
        java.nio.file.Files.createDirectories(incoming)
        val older = incoming.resolve("older.txt").also { it.writeText("old") }
        val newer = incoming.resolve("newer.txt").also { it.writeText("new") }
        java.nio.file.Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")))
        java.nio.file.Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2024-06-01T00:00:00Z")))
        val source =
            source(watermarkMode = WatermarkMode.MODIFIED_TIMESTAMP) { fileSystem, directory ->
                fileSystem.listAttributes(directory, recursive = true, matcher = null, subset = null)
                    .filter { it.regularFile }
                    .sortedByDescending { it.lastModifiedTime }
            }
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        val delivered = mutableMapOf<String, MuseEventEnvelope>()
        repeat(2) {
            val envelope = withTimeout(2_000) { channel.receive() }
            val name = assertIs<LocalFileAttributes>(envelope.event.attributes!!.value).fileName
            delivered[name] = envelope
        }
        val newerEnvelope = delivered.getValue("newer.txt")
        val olderEnvelope = delivered.getValue("older.txt")
        source.onResult(newerEnvelope.event, newerEnvelope.transactionContext)
        source.onResult(
            olderEnvelope.event.copy(
                error =
                    MuseError(
                        description = "failed",
                        detailedDescription = "failed",
                        errorType = FileErrorType.ANY,
                        cause = IllegalStateException("failed"),
                    ),
            ),
            olderEnvelope.transactionContext,
        )
        delivered.values.forEach { it.event.payload!!.asCursorStreamProvider()!!.releaseResources() }

        val retried = withTimeout(2_000) { channel.receive() }
        assertEquals("older.txt", assertIs<LocalFileAttributes>(retried.event.attributes!!.value).fileName)
        retried.event.payload!!.asCursorStreamProvider()!!.releaseResources()

        source.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `failed flow is dispatched again`() = runBlocking(Dispatchers.Default) {
        val incoming = workingDirectory.resolve("incoming")
        java.nio.file.Files.createDirectories(incoming)
        incoming.resolve("value.txt").writeText("one")
        val source = source()
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        val first = withTimeout(2_000) { channel.receive() }
        first.event.payload!!.asCursorStreamProvider()!!.releaseResources()
        source.onResult(
            first.event.copy(
                error =
                    MuseError(
                        description = "failed",
                        detailedDescription = "failed",
                        errorType = FileErrorType.ANY,
                        cause = IllegalStateException("failed"),
                    ),
            ),
            first.transactionContext,
        )

        val second = withTimeout(2_000) { channel.receive() }
        assertEquals("value.txt", assertIs<LocalFileAttributes>(second.event.attributes!!.value).fileName)
        second.event.payload!!.asCursorStreamProvider()!!.releaseResources()

        source.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `listener waits for startDelay before the first poll`() = runBlocking(Dispatchers.Default) {
        val incoming = workingDirectory.resolve("incoming")
        java.nio.file.Files.createDirectories(incoming)
        incoming.resolve("value.txt").writeText("one")
        val source = source(startDelayMillis = 500)
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val startedAt = System.nanoTime()
        val job = launch { source.start(channel) }

        val first = withTimeout(2_000) { channel.receive() }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(elapsedMillis >= 400, "startDelay must hold the first poll, elapsed ${elapsedMillis}ms")
        assertEquals("value.txt", assertIs<LocalFileAttributes>(first.event.attributes!!.value).fileName)
        first.event.payload!!.asCursorStreamProvider()!!.releaseResources()

        source.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `listener emits existing files once and emits them again after an update`() = runBlocking(Dispatchers.Default) {
        val incoming = workingDirectory.resolve("incoming")
        java.nio.file.Files.createDirectories(incoming)
        val file = incoming.resolve("value.txt").also { it.writeText("one") }
        val source = source()
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        val first = withTimeout(2_000) { channel.receive() }
        assertEquals("one", first.event.payload!!.asCursorStreamProvider()!!.openCursor().bufferedReader().use { it.readText() })
        assertEquals("value.txt", assertIs<LocalFileAttributes>(first.event.attributes!!.value).fileName)
        source.onResult(first.event, first.transactionContext)
        first.event.payload!!.asCursorStreamProvider()!!.releaseResources()

        kotlinx.coroutines.delay(80)
        assertTrue(channel.tryReceive().isFailure, "unchanged files must not be redelivered")

        file.writeText("two-updated")
        val updated = withTimeout(2_000) { channel.receive() }
        assertEquals(
            "two-updated",
            updated.event.payload!!.asCursorStreamProvider()!!.openCursor().bufferedReader().use { it.readText() },
        )
        updated.event.payload!!.asCursorStreamProvider()!!.releaseResources()

        source.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `listener auto deletes a file only after flow processing completes`() = runBlocking(Dispatchers.Default) {
        val incoming = workingDirectory.resolve("incoming")
        java.nio.file.Files.createDirectories(incoming)
        val file = incoming.resolve("value.txt").also { it.writeText("value") }
        val source = source(autoDelete = true)
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        val envelope = withTimeout(2_000) { channel.receive() }
        assertTrue(file.exists())

        source.onResult(envelope.event, envelope.transactionContext)

        assertFalse(file.exists())
        assertTrue(envelope.event.payload!!.asCursorStreamProvider()!!.isClosed)
        source.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `listener moves and renames processed files`() = runBlocking(Dispatchers.Default) {
        val incoming = workingDirectory.resolve("incoming")
        java.nio.file.Files.createDirectories(incoming)
        incoming.resolve("value.txt").writeText("value")
        val source = source(moveToDirectory = "archive", renameTo = "processed.txt")
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        val envelope = withTimeout(2_000) { channel.receive() }
        source.onResult(envelope.event, envelope.transactionContext)

        val archived = workingDirectory.resolve("archive/processed.txt")
        assertTrue(archived.exists())
        assertEquals("value", archived.readText())
        source.stop()
        job.cancelAndJoin()
    }

    @Test
    fun `equal modification timestamps remain eligible for a different path`() = runBlocking(Dispatchers.Default) {
        val incoming = workingDirectory.resolve("incoming")
        java.nio.file.Files.createDirectories(incoming)
        val stamp = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"))
        val one = incoming.resolve("one.txt").also { it.writeText("one") }
        val two = incoming.resolve("two.txt").also { it.writeText("two") }
        java.nio.file.Files.setLastModifiedTime(one, stamp)
        java.nio.file.Files.setLastModifiedTime(two, stamp)

        val source = source(watermarkMode = WatermarkMode.MODIFIED_TIMESTAMP)
        val channel = Channel<MuseEventEnvelope>(Channel.UNLIMITED)
        val job = launch { source.start(channel) }

        val names = mutableSetOf<String>()
        val modified = mutableSetOf<Instant>()
        repeat(2) {
            val envelope = withTimeout(2_000) { channel.receive() }
            val attributes = assertIs<LocalFileAttributes>(envelope.event.attributes!!.value)
            names += attributes.fileName
            modified += attributes.lastModifiedTime
            source.onResult(envelope.event, envelope.transactionContext)
            envelope.event.payload!!.asCursorStreamProvider()!!.releaseResources()
        }
        assertEquals(setOf("one.txt", "two.txt"), names)
        assertEquals(1, modified.size)

        kotlinx.coroutines.delay(80)
        assertTrue(channel.tryReceive().isFailure, "unchanged files must not be redelivered")

        source.stop()
        job.cancelAndJoin()
    }
}

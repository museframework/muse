package dev.museframework.connectors.sftp.source

import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.connectors.sftp.client.ApacheMinaSftpClient
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.config.SftpListenerConfig
import dev.museframework.sftp.api.model.SftpErrorType
import dev.museframework.sftp.api.model.SftpFileAttributes
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType
import dev.museframework.sftp.api.model.SftpPostAction
import dev.museframework.sftp.api.model.SftpWriteMode
import dev.museframework.sftp.testkit.EmbeddedSftpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SftpListenerTransportIT {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `listener polls streams and deletes after successful processing`() =
        runBlocking {
            withServer { server, connection, client ->
                client.write(
                    "/inbound/message.txt",
                    ByteArrayInputStream("listener-payload".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = true,
                )
                val source =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        postAction = SftpPostAction.Delete,
                    )
                val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val sourceJob = launch(Dispatchers.Default) { source.start(channel) }

                try {
                    val envelope = withTimeout(5_000) { channel.receive() }
                    val provider = assertIs<MuseCursorStreamProvider>(envelope.event.payload?.getOrNull())
                    assertEquals(
                        "listener-payload",
                        provider.openCursor().use { it.readAllBytes().toString(Charsets.UTF_8) },
                    )

                    source.onResult(MuseEvent(), envelope.transactionContext)

                    assertFalse(server.home.resolve("inbound/message.txt").exists())
                    provider.releaseResources()
                } finally {
                    source.stop()
                    sourceJob.cancelAndJoin()
                    channel.close()
                }
            }
        }

    @Test
    fun `listener moves matching files after successful processing`() =
        runBlocking {
            withServer { server, connection, client ->
                client.write(
                    "/inbound/keep.csv",
                    ByteArrayInputStream("csv".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = true,
                )
                client.write(
                    "/inbound/skip.txt",
                    ByteArrayInputStream("txt".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = false,
                )
                val source =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        matcher = SftpFileMatcher(filenamePattern = "*.csv", fileType = SftpFileType.REGULAR_FILE),
                        postAction = SftpPostAction.Move("/archive", overwrite = true),
                    )
                val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val sourceJob = launch(Dispatchers.Default) { source.start(channel) }

                try {
                    val envelope = withTimeout(5_000) { channel.receive() }
                    assertEquals(
                        "/inbound/keep.csv",
                        assertIs<SftpFileAttributes>(envelope.event.attributes?.getOrNull()).path,
                    )
                    source.onResult(MuseEvent(), envelope.transactionContext)

                    assertFalse(server.home.resolve("inbound/keep.csv").exists())
                    assertTrue(server.home.resolve("archive/keep.csv").exists())
                    assertTrue(server.home.resolve("inbound/skip.txt").exists())
                    assertNull(withTimeoutOrNull(250) { channel.receive() })
                } finally {
                    source.stop()
                    sourceJob.cancelAndJoin()
                    channel.close()
                }
            }
        }

    @Test
    fun `flat listener ignores nested files while recursive listener emits them`() =
        runBlocking {
            withServer { _, connection, client ->
                client.createDirectory("/inbound/nested")
                client.write(
                    "/inbound/nested/deep.txt",
                    ByteArrayInputStream("nested".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = false,
                )

                val flat =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        recursive = false,
                        postAction = SftpPostAction.None,
                    )
                val flatChannel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val flatJob = launch(Dispatchers.Default) { flat.start(flatChannel) }
                try {
                    assertNull(withTimeoutOrNull(300) { flatChannel.receive() })
                } finally {
                    flat.stop()
                    flatJob.cancelAndJoin()
                    flatChannel.close()
                }

                val recursive =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        recursive = true,
                        postAction = SftpPostAction.None,
                    )
                val recursiveChannel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val recursiveJob = launch(Dispatchers.Default) { recursive.start(recursiveChannel) }
                try {
                    val envelope = withTimeout(5_000) { recursiveChannel.receive() }
                    val provider = assertIs<MuseCursorStreamProvider>(envelope.event.payload?.getOrNull())
                    assertEquals(
                        "nested",
                        provider.openCursor().use { it.readAllBytes().toString(Charsets.UTF_8) },
                    )
                    provider.releaseResources()
                } finally {
                    recursive.stop()
                    recursiveJob.cancelAndJoin()
                    recursiveChannel.close()
                }
            }
        }

    @Test
    fun `listener keeps the trigger when post action on failure is disabled`() =
        runBlocking {
            withServer { server, connection, client ->
                client.write(
                    "/inbound/fail.txt",
                    ByteArrayInputStream("payload".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = true,
                )
                val source =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        postAction = SftpPostAction.Delete,
                        applyPostActionOnFailure = false,
                    )
                val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val sourceJob = launch(Dispatchers.Default) { source.start(channel) }

                try {
                    val envelope = withTimeout(5_000) { channel.receive() }
                    source.onResult(
                        MuseEvent(error = processingError()),
                        envelope.transactionContext,
                    )
                    assertTrue(server.home.resolve("inbound/fail.txt").exists())
                } finally {
                    source.stop()
                    sourceJob.cancelAndJoin()
                    channel.close()
                }
            }
        }

    @Test
    fun `listener deletes the trigger when post action on failure is enabled`() =
        runBlocking {
            withServer { server, connection, client ->
                client.write(
                    "/inbound/fail.txt",
                    ByteArrayInputStream("payload".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = true,
                )
                val source =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        postAction = SftpPostAction.Delete,
                        applyPostActionOnFailure = true,
                    )
                val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val sourceJob = launch(Dispatchers.Default) { source.start(channel) }

                try {
                    val envelope = withTimeout(5_000) { channel.receive() }
                    source.onResult(
                        MuseEvent(error = processingError()),
                        envelope.transactionContext,
                    )
                    assertFalse(server.home.resolve("inbound/fail.txt").exists())
                } finally {
                    source.stop()
                    sourceJob.cancelAndJoin()
                    channel.close()
                }
            }
        }

    @Test
    fun `listener does not emit a file again after the watermark is stored`() =
        runBlocking {
            withServer { server, connection, client ->
                client.write(
                    "/inbound/stable.txt",
                    ByteArrayInputStream("once".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = true,
                )
                val source =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        postAction = SftpPostAction.None,
                        watermarkEnabled = true,
                        sizeCheckDelayMs = 10,
                        pollIntervalMs = 40,
                    )
                val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val sourceJob = launch(Dispatchers.Default) { source.start(channel) }

                try {
                    val envelope = withTimeout(5_000) { channel.receive() }
                    val provider = assertIs<MuseCursorStreamProvider>(envelope.event.payload?.getOrNull())
                    source.onResult(MuseEvent(), envelope.transactionContext)
                    provider.releaseResources()

                    assertNull(withTimeoutOrNull(400) { channel.receive() })
                    assertTrue(server.home.resolve("inbound/stable.txt").exists())
                } finally {
                    source.stop()
                    sourceJob.cancelAndJoin()
                    channel.close()
                }
            }
        }

    @Test
    fun `listener skips a file whose size changes during the stability wait`() =
        runBlocking {
            withServer { server, connection, client ->
                client.write(
                    "/inbound/growing.txt",
                    ByteArrayInputStream("v1".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = true,
                )
                val source =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        postAction = SftpPostAction.None,
                        watermarkEnabled = false,
                        sizeCheckDelayMs = 1_200,
                        pollIntervalMs = 30_000,
                    )
                val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val sourceJob = launch(Dispatchers.Default) { source.start(channel) }

                try {
                    delay(300)
                    client.write(
                        "/inbound/growing.txt",
                        ByteArrayInputStream("-v2".toByteArray()),
                        SftpWriteMode.APPEND,
                        createParentDirectories = false,
                    )
                    assertNull(withTimeoutOrNull(1_500) { channel.receive() })
                    assertEquals("v1-v2", server.home.resolve("inbound/growing.txt").readText())
                } finally {
                    source.stop()
                    sourceJob.cancelAndJoin()
                    channel.close()
                }
            }
        }

    @Test
    fun `listener moves the trigger when post action on failure is enabled`() =
        runBlocking {
            withServer { server, connection, client ->
                client.write(
                    "/inbound/fail.txt",
                    ByteArrayInputStream("payload".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = true,
                )
                val source =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        postAction = SftpPostAction.Move("/archive", overwrite = true),
                        applyPostActionOnFailure = true,
                    )
                val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val sourceJob = launch(Dispatchers.Default) { source.start(channel) }

                try {
                    val envelope = withTimeout(5_000) { channel.receive() }
                    source.onResult(
                        MuseEvent(error = processingError()),
                        envelope.transactionContext,
                    )
                    assertFalse(server.home.resolve("inbound/fail.txt").exists())
                    assertTrue(server.home.resolve("archive/fail.txt").exists())
                } finally {
                    source.stop()
                    sourceJob.cancelAndJoin()
                    channel.close()
                }
            }
        }

    @Test
    fun `listener keeps both files when a move post action cannot overwrite`() =
        runBlocking {
            withServer { server, connection, client ->
                client.write(
                    "/inbound/keep.csv",
                    ByteArrayInputStream("new".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = true,
                )
                client.write(
                    "/archive/keep.csv",
                    ByteArrayInputStream("old".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = true,
                )
                val source =
                    listener(
                        connection,
                        client,
                        directory = "/inbound",
                        matcher = SftpFileMatcher(filenamePattern = "*.csv", fileType = SftpFileType.REGULAR_FILE),
                        postAction = SftpPostAction.Move("/archive", overwrite = false),
                        pollIntervalMs = 30_000,
                    )
                val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
                val sourceJob = launch(Dispatchers.Default) { source.start(channel) }

                try {
                    val envelope = withTimeout(5_000) { channel.receive() }
                    source.onResult(MuseEvent(), envelope.transactionContext)
                    assertTrue(server.home.resolve("inbound/keep.csv").exists())
                    assertEquals("old", server.home.resolve("archive/keep.csv").readText())
                } finally {
                    source.stop()
                    sourceJob.cancelAndJoin()
                    channel.close()
                }
            }
        }

    private suspend fun withServer(
        block: suspend (
            EmbeddedSftpServer,
            SftpConnectionConfig,
            ApacheMinaSftpClient,
        ) -> Unit,
    ) {
        val server = EmbeddedSftpServer(temporaryDirectory.resolve("server-${System.nanoTime()}").also {
            it.createDirectories()
        })
        server.start()
        val connection =
            SftpConnectionConfig(
                host = EmbeddedSftpServer.HOST,
                port = server.port,
                username = EmbeddedSftpServer.USERNAME,
                password = EmbeddedSftpServer.PASSWORD,
                hostKeyChecking = SftpHostKeyChecking.INSECURE,
                operationTimeoutMs = 5_000,
            )
        val client = ApacheMinaSftpClient(connection)
        try {
            block(server, connection, client)
        } finally {
            server.close()
        }
    }

    private fun listener(
        connection: SftpConnectionConfig,
        client: ApacheMinaSftpClient,
        directory: String,
        recursive: Boolean = true,
        matcher: SftpFileMatcher = SftpFileMatcher(fileType = SftpFileType.REGULAR_FILE),
        postAction: SftpPostAction,
        applyPostActionOnFailure: Boolean = false,
        watermarkEnabled: Boolean = true,
        sizeCheckDelayMs: Long? = 10,
        pollIntervalMs: Long = 25,
    ) = SftpListenerMuseSource(
        SftpListenerConfig(
            connection = SftpConnectionRef.ByReference(connection),
            directory = directory,
            recursive = recursive,
            matcher = matcher,
            pollIntervalMs = pollIntervalMs,
            sizeCheckDelayMs = sizeCheckDelayMs,
            watermarkEnabled = watermarkEnabled,
            postAction = postAction,
            applyPostActionOnFailure = applyPostActionOnFailure,
        ),
        client,
    )

    private fun processingError() =
        MuseError(
            description = "processing failed",
            detailedDescription = "forced failure for post-action coverage",
            errorType = SftpErrorType.OPERATION_FAILED,
            cause = IllegalStateException("forced"),
        )
}

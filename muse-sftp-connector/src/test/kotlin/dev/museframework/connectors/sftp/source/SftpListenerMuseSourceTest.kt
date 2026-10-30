package dev.museframework.connectors.sftp.source

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.connectors.sftp.client.MuseSftpClient
import dev.museframework.connectors.sftp.client.SftpReadHandle
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.config.SftpListenerConfig
import dev.museframework.sftp.api.model.SftpFileAttributes
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpPostAction
import dev.museframework.sftp.api.model.SftpTransferRequest
import dev.museframework.sftp.api.model.SftpWriteMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SftpListenerMuseSourceTest {
    @Test
    fun `exceptional request completion allows a later poll to retry without a post action`() = runTest(timeout = 5.seconds) {
        val client = ListenerClient(fileAttributes("/inbound/order.csv"))
        val source = SftpListenerMuseSource(listenerConfig(), client)
        val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
        val job = launch { source.start(channel) }
        try {
            val first = channel.receive()
            first.replyTo.completeExceptionally(IllegalStateException("request failed"))
            first.executionResources.releaseAll()
            val retry = channel.receive()
            assertEquals(first.event.attributes, retry.event.attributes)
            assertEquals(null, client.moveCall)
            assertEquals(0, client.openReadCalls)
            retry.executionResources.releaseAll()
        } finally {
            source.stop()
            job.cancel()
            channel.close()
        }
    }

    @Test
    fun `listener emits stable files once and moves them after successful processing`() =
        runTest {
            val attributes = fileAttributes("/inbound/order.csv")
            val client = ListenerClient(attributes)
            val source =
                SftpListenerMuseSource(
                    SftpListenerConfig(
                        connection = connectionRef(),
                        directory = "/inbound",
                        pollIntervalMs = 100,
                        sizeCheckDelayMs = 10,
                        watermarkEnabled = true,
                        postAction = SftpPostAction.Move("/processed", overwrite = true),
                    ),
                    client,
                )
            val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
            val sourceJob = launch { source.start(channel) }

            val envelope = channel.receive()
            val provider = assertIs<MuseCursorStreamProvider>(envelope.event.payload?.getOrNull())
            assertEquals("file-content", provider.openCursor().use { it.readAllBytes().toString(Charsets.UTF_8) })
            assertEquals("application/octet-stream", envelope.event.payload?.mimeType)
            assertEquals("file-content", provider.openCursor().use { it.readAllBytes().toString(Charsets.UTF_8) })
            assertEquals(2, client.openReadCalls, "the default source remains repeatable")
            assertEquals(attributes, envelope.event.attributes?.getOrNull())
            source.onResult(MuseEvent(), envelope.transactionContext)

            advanceTimeBy(200)
            runCurrent()
            assertTrue(channel.tryReceive().isFailure)
            assertEquals(MoveCall("/inbound/order.csv", "/processed", true), client.moveCall)
            assertTrue(client.statCalls >= 1)

            source.stop()
            sourceJob.cancel()
            provider.releaseResources()
            channel.close()
        }

    @Test
    fun `listener preserves CSV reader properties without opening remote content`() =
        runTest {
            val client = ListenerClient(fileAttributes("/inbound/order.csv"))
            val source = SftpListenerMuseSource(
                listenerConfig().copy(
                    outputMimeType = "application/csv; separator=|; streaming=true; header=false",
                    nonRepeatable = true,
                ),
                client,
            )
            val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
            val sourceJob = launch { source.start(channel) }

            val envelope = channel.receive()
            val payload = checkNotNull(envelope.event.payload)
            assertEquals("application/csv; separator=|; streaming=true; header=false", payload.mimeType)
            val provider = assertIs<MuseCursorStreamProvider>(payload.getOrNull())
            assertEquals(0, client.openReadCalls, "MIME metadata must not eagerly read the remote file")
            assertEquals("file-content", provider.openCursor().use { it.readAllBytes().toString(Charsets.UTF_8) })
            assertEquals(1, client.openReadCalls)
            assertFailsWith<StreamingException> { provider.openCursor() }
            assertEquals(1, client.openReadCalls, "a second cursor must not reopen the remote file")

            source.stop()
            sourceJob.cancel()
            envelope.executionResources.releaseAll()
            assertTrue(provider.isClosed)
            channel.close()
        }

    @Test
    fun `listener releases the event resources of an envelope the channel refuses`() =
        runTest {
            val client = ListenerClient(fileAttributes("/inbound/order.csv"))
            val source = SftpListenerMuseSource(listenerConfig().copy(nonRepeatable = true), client)
            val channel = RefusingSendChannel()

            val sourceJob = launch { source.start(channel) }
            channel.awaitRefused()
            source.stop()
            sourceJob.cancel()

            val refused = checkNotNull(channel.refusedEnvelope)
            val provider = assertIs<MuseCursorStreamProvider>(refused.event.payload?.getOrNull())
            assertEquals(0, client.openReadCalls, "releasing an unopened provider must not open remote content")
            assertTrue(refused.executionResources.isReleased, "the refused envelope must release its registry")
            assertTrue(provider.isClosed, "the registry-owned provider must be released through the registry")
        }

    @Test
    fun `listener propagates cancellation raised while checking file stability`() =
        runTest {
            val first = fileAttributes("/inbound/first.csv")
            val second = fileAttributes("/inbound/second.csv")
            val client =
                ListenerClient(first, files = listOf(first, second), statFailure = CancellationException("cancelled"))
            val source = SftpListenerMuseSource(listenerConfig(sizeCheckDelayMs = 10), client)
            val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)

            assertFailsWith<CancellationException> { source.start(channel) }

            assertEquals(1, client.statCalls, "the poll must stop at the cancelled file")
            assertTrue(channel.tryReceive().isFailure, "no later file may be emitted after cancellation")
        }

    @Test
    fun `listener propagates cancellation raised while applying the post action`() =
        runTest {
            val attributes = fileAttributes("/inbound/order.csv")
            val client = ListenerClient(attributes, moveFailure = CancellationException("cancelled"))
            val source =
                SftpListenerMuseSource(
                    listenerConfig(postAction = SftpPostAction.Move("/processed", overwrite = true)),
                    client,
                )
            val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
            val sourceJob = launch { source.start(channel) }

            val envelope = channel.receive()
            assertFailsWith<CancellationException> { source.onResult(MuseEvent(), envelope.transactionContext) }

            source.stop()
            sourceJob.cancel()
            envelope.executionResources.releaseAll()
            channel.close()
        }

    private fun listenerConfig(
        sizeCheckDelayMs: Long? = null,
        postAction: SftpPostAction = SftpPostAction.None,
    ) = SftpListenerConfig(
        connection = connectionRef(),
        directory = "/inbound",
        pollIntervalMs = 100,
        sizeCheckDelayMs = sizeCheckDelayMs,
        postAction = postAction,
    )

    /** Refuses every send while keeping the refused envelope observable. */
    private class RefusingSendChannel(
        private val delegate: Channel<MuseEventEnvelope> =
            Channel<MuseEventEnvelope>(Channel.UNLIMITED).also { it.close() },
    ) : SendChannel<MuseEventEnvelope> by delegate {
        private val refused = CompletableDeferred<Unit>()

        @Volatile
        var refusedEnvelope: MuseEventEnvelope? = null
            private set

        override fun trySend(element: MuseEventEnvelope): ChannelResult<Unit> {
            refusedEnvelope = element
            val result = delegate.trySend(element)
            if (result.isFailure) refused.complete(Unit)
            return result
        }

        suspend fun awaitRefused() = refused.await()
    }

    private class ListenerClient(
        private val attributes: SftpFileAttributes,
        private val files: List<SftpFileAttributes> = listOf(attributes),
        private val statFailure: Throwable? = null,
        private val moveFailure: Throwable? = null,
    ) : MuseSftpClient {
        var moveCall: MoveCall? = null
        var statCalls = 0
        var openReadCalls = 0

        override fun stat(path: String): SftpFileAttributes {
            statCalls++
            statFailure?.let { throw it }
            return attributes
        }

        override fun list(directoryPath: String, recursive: Boolean, matcher: SftpFileMatcher) = files

        override fun openRead(path: String): SftpReadHandle {
            openReadCalls++
            return SftpReadHandle(ByteArrayInputStream("file-content".toByteArray()), attributes) {}
        }

        override fun move(transfer: SftpTransferRequest) {
            moveFailure?.let { throw it }
            moveCall = MoveCall(transfer.sourcePath, transfer.targetDirectory, transfer.overwrite)
        }

        override fun write(path: String, content: InputStream, mode: SftpWriteMode, createParentDirectories: Boolean) = Unit
        override fun copy(transfer: SftpTransferRequest) = Unit
        override fun delete(path: String) = Unit
        override fun rename(path: String, newName: String, overwrite: Boolean) = Unit
        override fun createDirectory(path: String) = Unit
    }

    private data class MoveCall(
        val source: String,
        val targetDirectory: String,
        val overwrite: Boolean,
    )

    private fun connectionRef() =
        SftpConnectionRef.ByReference(
            SftpConnectionConfig(
                host = "localhost",
                username = "muse",
                password = "secret",
                hostKeyChecking = SftpHostKeyChecking.INSECURE,
            ),
        )

    private fun fileAttributes(path: String) =
        SftpFileAttributes(
            path = path,
            name = path.substringAfterLast('/'),
            size = 12,
            modifiedAt = Instant.parse("2026-08-01T12:00:00Z"),
            regularFile = true,
            directory = false,
            symbolicLink = false,
            permissions = 0b110100100,
        )
}

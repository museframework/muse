package dev.museframework.connectors.sftp.source

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SftpListenerBehaviorIT {
    @Test
    fun `watermark skips a file that was already processed with the same version`() =
        runTest {
            val attributes = fileAttributes("/inbound/order.csv", size = 12)
            val client = BehaviorClient(listOf(attributes), attributes)
            val source =
                SftpListenerMuseSource(
                    SftpListenerConfig(
                        connection = connectionRef(),
                        directory = "/inbound",
                        pollIntervalMs = 50,
                        sizeCheckDelayMs = null,
                        watermarkEnabled = true,
                        postAction = SftpPostAction.None,
                    ),
                    client,
                )
            val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
            val job = launch { source.start(channel) }

            val first = channel.receive()
            source.onResult(MuseEvent(), first.transactionContext)
            assertIs<MuseCursorStreamProvider>(first.event.payload?.getOrNull()).releaseResources()

            advanceTimeBy(200)
            runCurrent()
            assertNull(withTimeoutOrNull(50) { channel.receive() })

            source.stop()
            job.cancel()
            channel.close()
        }

    @Test
    fun `size check skips a file that is still changing`() =
        runTest {
            val first = fileAttributes("/inbound/growing.csv", size = 10)
            val second = fileAttributes("/inbound/growing.csv", size = 20)
            val client = BehaviorClient(listOf(first), second)
            val source =
                SftpListenerMuseSource(
                    SftpListenerConfig(
                        connection = connectionRef(),
                        directory = "/inbound",
                        pollIntervalMs = 50,
                        sizeCheckDelayMs = 10,
                        watermarkEnabled = false,
                        postAction = SftpPostAction.None,
                    ),
                    client,
                )
            val channel = Channel<MuseEventEnvelope>(Channel.BUFFERED)
            val job = launch { source.start(channel) }

            try {
                // Wait for the next poll so the size check has completed before asserting no event was emitted.
                client.secondPollStarted.await()
                assertNull(channel.tryReceive().getOrNull())
                assertTrue(client.statCalls >= 1)
            } finally {
                source.stop()
                job.cancelAndJoin()
                channel.close()
            }
        }

    private class BehaviorClient(
        private val listed: List<SftpFileAttributes>,
        private val statResult: SftpFileAttributes,
    ) : MuseSftpClient {
        var statCalls = 0
        val secondPollStarted = CompletableDeferred<Unit>()
        private var listCalls = 0

        override fun stat(path: String): SftpFileAttributes {
            statCalls++
            return statResult
        }

        override fun list(
            directoryPath: String,
            recursive: Boolean,
            matcher: SftpFileMatcher,
        ): List<SftpFileAttributes> {
            listCalls++
            if (listCalls == 2) secondPollStarted.complete(Unit)
            return listed
        }

        override fun openRead(path: String): SftpReadHandle =
            SftpReadHandle(ByteArrayInputStream("content".toByteArray()), listed.single()) {}

        override fun write(
            path: String,
            content: InputStream,
            mode: SftpWriteMode,
            createParentDirectories: Boolean,
        ) = Unit

        override fun copy(transfer: SftpTransferRequest) = Unit

        override fun move(transfer: SftpTransferRequest) = Unit

        override fun delete(path: String) = Unit

        override fun rename(
            path: String,
            newName: String,
            overwrite: Boolean,
        ) = Unit

        override fun createDirectory(path: String) = Unit
    }

    private fun connectionRef() =
        SftpConnectionRef.ByReference(
            SftpConnectionConfig(
                host = "localhost",
                username = "muse",
                password = "secret",
                hostKeyChecking = SftpHostKeyChecking.INSECURE,
            ),
        )

    private fun fileAttributes(
        path: String,
        size: Long,
    ) = SftpFileAttributes(
        path = path,
        name = path.substringAfterLast('/'),
        size = size,
        modifiedAt = Instant.parse("2026-08-01T12:00:00Z"),
        regularFile = true,
        directory = false,
        symbolicLink = false,
        permissions = 0b110100100,
    )
}

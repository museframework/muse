package dev.museframework.sftp.testkit

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControllableSftpServerTest {
    @Test
    fun `stream write succeeds with exact bytes and open handles return to baseline`() {
        Files.createTempDirectory("muse-controllable-sftp-normal-").use { base ->
            ControllableSftpServer(base).use { server ->
                server.start()
                val baselineHandles = server.openHandleCount
                assertEquals(0, server.activeSessionCount)

                Files.createDirectories(server.home.resolve("upload"))
                val payload = ByteArray(128 * 1024) { it.toByte() }
                withAuthenticatedSftp(server) { sftp ->
                    sftp.write("upload/normal.bin").use { output ->
                        output.write(payload)
                    }
                }
                awaitHandleDrain(server, baselineHandles)
                assertContentEquals(payload, Files.readAllBytes(server.home.resolve("upload/normal.bin")))
                assertEquals(baselineHandles, server.openHandleCount)
                assertEquals(0, server.activeSessionCount)
                assertEquals(1, server.completedWriteCount("upload/normal.bin"))
            }
        }
    }

    @Test
    fun `armed write failure accepts a prefix pauses then throws after release`() {
        Files.createTempDirectory("muse-controllable-sftp-fault-").use { base ->
            ControllableSftpServer(base).use { server ->
                server.start()
                server.armWriteFailure(afterBytes = 65_536L)
                Files.createDirectories(server.home.resolve("upload"))

                val payload = ByteArray(1024 * 1024) { it.toByte() }
                val writeStarted = CountDownLatch(1)
                val writer = Executors.newSingleThreadExecutor()
                try {
                    val writeFailure =
                        writer.submit {
                            writeStarted.countDown()
                            withAuthenticatedSftp(server) { sftp ->
                                sftp.write("upload/fault.bin").use { output ->
                                    var offset = 0
                                    while (offset < payload.size) {
                                        val chunk = minOf(WRITE_CHUNK_SIZE, payload.size - offset)
                                        output.write(payload, offset, chunk)
                                        offset += chunk
                                    }
                                }
                            }
                        }

                    assertTrue(writeStarted.await(5, TimeUnit.SECONDS), "client write did not start")
                    val observation = server.awaitWriteFailureReady(Duration.ofSeconds(10))
                    assertEquals("upload/fault.bin", observation.remotePath)
                    assertTrue(observation.acceptedBytes > 0L)
                    assertEquals(65_536L, observation.acceptedBytes)
                    assertEquals(65_536L, Files.size(server.home.resolve("upload/fault.bin")))
                    assertEquals(0, server.completedWriteCount("upload/fault.bin"))

                    assertThrows<TimeoutException> {
                        writeFailure.get(500, TimeUnit.MILLISECONDS)
                    }

                    server.releaseWriteFailure()
                    val failure = assertThrows<ExecutionException> {
                        writeFailure.get(10, TimeUnit.SECONDS)
                    }
                    assertTrue(failure.cause is IOException)
                    assertEquals(65_536L, Files.size(server.home.resolve("upload/fault.bin")))
                    awaitHandleDrain(server, 0)
                    assertEquals(0, server.openHandleCount)
                } finally {
                    writer.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `crossing single SFTP write faults after prefix instead of succeeding truncated`() {
        Files.createTempDirectory("muse-controllable-sftp-crossing-").use { base ->
            ControllableSftpServer(base).use { server ->
                server.start()
                server.armWriteFailure(afterBytes = 65_536L)
                Files.createDirectories(server.home.resolve("upload"))

                val payload = ByteArray(65_537) { (it % 251).toByte() }
                val writer = Executors.newSingleThreadExecutor()
                try {
                    val writeFailure =
                        writer.submit {
                            withAuthenticatedSftp(server) { sftp ->
                                sftp.write("upload/crossing.bin").use { output ->
                                    output.write(payload)
                                }
                            }
                        }

                    val observation = server.awaitWriteFailureReady(Duration.ofSeconds(10))
                    assertEquals("upload/crossing.bin", observation.remotePath)
                    assertEquals(65_536L, observation.acceptedBytes)
                    assertEquals(65_536L, Files.size(server.home.resolve("upload/crossing.bin")))
                    assertEquals(0, server.completedWriteCount("upload/crossing.bin"))

                    server.releaseWriteFailure()
                    val failure = assertThrows<ExecutionException> {
                        writeFailure.get(10, TimeUnit.SECONDS)
                    }
                    assertTrue(failure.cause is IOException)
                    awaitHandleDrain(server, 0)
                } finally {
                    writer.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `concurrent handles on the same path observe only their own accepted bytes`() {
        Files.createTempDirectory("muse-controllable-sftp-multi-handle-").use { base ->
            ControllableSftpServer(base).use { server ->
                server.start()
                server.armWriteFailure(afterBytes = 32_768L)
                Files.createDirectories(server.home.resolve("upload"))

                val chunk = ByteArray(32 * 1024) { 1 }
                val writer = Executors.newFixedThreadPool(2)
                try {
                    val first =
                        writer.submit {
                            withAuthenticatedSftp(server) { sftp ->
                                sftp.write("upload/shared.bin").use { output ->
                                    output.write(chunk)
                                    // Cross this handle's threshold and wait at the fault gate
                                    // before any peer handle opens the same path.
                                    output.write(byteArrayOf(1))
                                }
                            }
                        }

                    val observation = server.awaitWriteFailureReady(Duration.ofSeconds(10))
                    assertEquals(32_768L, observation.acceptedBytes)
                    assertTrue(observation.acceptedBytes < 65_536L)

                    val second =
                        writer.submit {
                            withAuthenticatedSftp(server) { sftp ->
                                sftp.write("upload/shared.bin").use { output ->
                                    output.write(chunk)
                                }
                            }
                        }
                    second.get(10, TimeUnit.SECONDS)

                    server.releaseWriteFailure()
                    val firstFailure = assertThrows<ExecutionException> { first.get(10, TimeUnit.SECONDS) }
                    assertTrue(firstFailure.cause is IOException)
                    awaitHandleDrain(server, 0)
                } finally {
                    writer.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `clearWriteFailure releases every concurrent gated transfer`() {
        Files.createTempDirectory("muse-controllable-sftp-concurrent-clear-").use { base ->
            ControllableSftpServer(base).use { server ->
                server.start()
                server.armWriteFailure(afterBytes = 65_536L)
                Files.createDirectories(server.home.resolve("upload"))

                val payload = ByteArray(65_537) { it.toByte() }
                val writer = Executors.newFixedThreadPool(2)
                try {
                    val first =
                        writer.submit {
                            withAuthenticatedSftp(server) { sftp ->
                                writeUntilFault(sftp, "upload/concurrent-a.bin", payload)
                            }
                        }
                    server.awaitWriteFailureReady(Duration.ofSeconds(10))
                    awaitPendingReleaseGateCount(server, expected = 1)

                    val second =
                        writer.submit {
                            withAuthenticatedSftp(server) { sftp ->
                                writeUntilFault(sftp, "upload/concurrent-b.bin", payload)
                            }
                        }
                    server.awaitWriteFailureReady(Duration.ofSeconds(10))
                    awaitWriteFailureGateWaiters(server, minimum = 2)
                    awaitPendingReleaseGateCount(server, expected = 2)
                    server.clearWriteFailure()

                    listOf(first, second).forEach { task ->
                        val failure = assertThrows<ExecutionException> { task.get(10, TimeUnit.SECONDS) }
                        assertTrue(failure.cause is IOException)
                    }
                    awaitHandleDrain(server, 0)
                } finally {
                    writer.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `write failure stays armed until cleared without rereleasing each retry`() {
        Files.createTempDirectory("muse-controllable-sftp-persist-").use { base ->
            ControllableSftpServer(base).use { server ->
                server.start()
                server.armWriteFailure(afterBytes = 65_536L)
                Files.createDirectories(server.home.resolve("upload"))

                val payload = ByteArray(1024 * 1024) { it.toByte() }

                val firstObservation = runFirstFaultedWriteWithRelease(server, payload, "upload/persist-a.bin")
                assertEquals(65_536L, firstObservation.acceptedBytes)

                runSubsequentFaultedWriteWithoutRelease(server, payload, "upload/persist-b.bin")

                server.clearWriteFailure()
                withAuthenticatedSftp(server) { sftp ->
                    sftp.write("upload/persist-c.bin").use { output ->
                        output.write(payload)
                    }
                }
                assertContentEquals(payload, Files.readAllBytes(server.home.resolve("upload/persist-c.bin")))
                assertEquals(1, server.completedWriteCount("upload/persist-c.bin"))
                awaitHandleDrain(server, 0)
            }
        }
    }

    @Test
    fun `reads do not increment completed write count`() {
        Files.createTempDirectory("muse-controllable-sftp-read-count-").use { base ->
            ControllableSftpServer(base).use { server ->
                server.start()
                Files.createDirectories(server.home.resolve("upload"))
                Files.write(server.home.resolve("upload/read-me.bin"), byteArrayOf(1, 2, 3))

                withAuthenticatedSftp(server) { sftp ->
                    sftp.read("upload/read-me.bin").use { input ->
                        input.readAllBytes()
                    }
                }
                assertEquals(0, server.completedWriteCount("upload/read-me.bin"))
                awaitHandleDrain(server, 0)
            }
        }
    }

    @Test
    fun `concurrent release during gate admission does not strand late waiter`() {
        Files.createTempDirectory("muse-controllable-sftp-gate-race-").use { base ->
            ControllableSftpServer(base).use { server ->
                server.start()
                server.armWriteFailure(afterBytes = 65_536L)
                Files.createDirectories(server.home.resolve("upload"))

                installPausingPendingReleaseGateQueue(server)

                val payload = ByteArray(65_537) { it.toByte() }
                val writer = Executors.newFixedThreadPool(2)
                val releaserThread = java.util.concurrent.atomic.AtomicReference<Thread>()
                val releaser =
                    Executors.newSingleThreadExecutor { runnable ->
                        Thread(runnable, "controllable-sftp-gate-releaser").also { releaserThread.set(it) }
                    }
                try {
                    val first =
                        writer.submit {
                            withAuthenticatedSftp(server) { sftp ->
                                sftp.write("upload/race-a.bin").use { output ->
                                    output.write(payload)
                                }
                            }
                        }
                    server.awaitWriteFailureReady(Duration.ofSeconds(10))
                    awaitPendingReleaseGateCount(server, expected = 1)

                    val pausingQueue = pendingReleaseGateQueue(server) as PausingPendingReleaseGateQueue
                    val second =
                        writer.submit {
                            withAuthenticatedSftp(server) { sftp ->
                                sftp.write("upload/race-b.bin").use { output ->
                                    output.write(payload)
                                }
                            }
                        }
                    server.awaitWriteFailureReady(Duration.ofSeconds(10))
                    assertTrue(pausingQueue.beforeSecondAdd.await(5, TimeUnit.SECONDS))

                    val releaseAttemptStarted = CountDownLatch(1)
                    val release =
                        releaser.submit {
                            releaseAttemptStarted.countDown()
                            server.releaseWriteFailure()
                        }
                    assertTrue(releaseAttemptStarted.await(5, TimeUnit.SECONDS))
                    awaitThreadBlocked(releaserThread.get(), Duration.ofSeconds(5))
                    pausingQueue.allowSecondAdd.countDown()
                    assertTrue(pausingQueue.secondAdded.await(5, TimeUnit.SECONDS))
                    release.get(10, TimeUnit.SECONDS)

                    listOf(first, second).forEach { task ->
                        val failure = assertThrows<ExecutionException> { task.get(10, TimeUnit.SECONDS) }
                        assertTrue(failure.cause is IOException)
                    }
                    assertEquals(0, pausingQueue.size)
                    awaitHandleDrain(server, 0)
                } finally {
                    releaser.shutdownNow()
                    writer.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `exceptional delegate close does not count as completed write`() {
        Files.createTempDirectory("muse-controllable-sftp-close-fail-").use { base ->
            val accessor =
                object : SftpFileSystemAccessor by SftpFileSystemAccessor.DEFAULT {
                    override fun openFile(
                        proxy: SftpSubsystemProxy,
                        handle: org.apache.sshd.sftp.server.FileHandle,
                        file: Path,
                        sshFile: String,
                        options: Set<OpenOption>,
                        vararg attrs: FileAttribute<*>,
                    ): SeekableByteChannel {
                        val delegate = SftpFileSystemAccessor.DEFAULT.openFile(proxy, handle, file, sshFile, options, *attrs)
                        return FailingCloseChannel(delegate)
                    }
                }
            createControllableSftpServerWithAccessor(base, accessor).use { server ->
                server.start()
                Files.createDirectories(server.home.resolve("upload"))
                assertThrows<IOException> {
                    withAuthenticatedSftp(server) { sftp ->
                        sftp.write("upload/close-fail.bin").use { output ->
                            output.write(byteArrayOf(1, 2, 3))
                        }
                    }
                }
                assertEquals(0, server.completedWriteCount("upload/close-fail.bin"))
                awaitHandleDrain(server, 0)
            }
        }
    }

    @Test
    fun `error delegate close failure does not count as completed write`() {
        Files.createTempDirectory("muse-controllable-sftp-error-close-").use { base ->
            val injectedClose = CountDownLatch(1)
            val accessor =
                object : SftpFileSystemAccessor by SftpFileSystemAccessor.DEFAULT {
                    override fun openFile(
                        proxy: SftpSubsystemProxy,
                        handle: org.apache.sshd.sftp.server.FileHandle,
                        file: Path,
                        sshFile: String,
                        options: Set<OpenOption>,
                        vararg attrs: FileAttribute<*>,
                    ): SeekableByteChannel {
                        val delegate = SftpFileSystemAccessor.DEFAULT.openFile(proxy, handle, file, sshFile, options, *attrs)
                        return ErrorFailingCloseChannel(delegate, injectedClose)
                    }
                }
            createControllableSftpServerWithAccessor(base, accessor).use { server ->
                server.start()
                Files.createDirectories(server.home.resolve("upload"))
                val writer = Executors.newSingleThreadExecutor()
                try {
                    val transfer =
                        writer.submit {
                            withAuthenticatedSftp(server) { sftp ->
                                sftp.write("upload/error-close-fail.bin").use { output ->
                                    output.write(byteArrayOf(1, 2, 3))
                                }
                            }
                        }
                    assertTrue(
                        injectedClose.await(10, TimeUnit.SECONDS),
                        "Injected AssertionError close was never reached",
                    )
                    awaitHandleDrain(server, 0)
                    assertEquals(0, server.completedWriteCount("upload/error-close-fail.bin"))
                    transfer.cancel(true)
                } finally {
                    writer.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `unchecked delegate close failure does not count as completed write`() {
        Files.createTempDirectory("muse-controllable-sftp-unchecked-close-").use { base ->
            val accessor =
                object : SftpFileSystemAccessor by SftpFileSystemAccessor.DEFAULT {
                    override fun openFile(
                        proxy: SftpSubsystemProxy,
                        handle: org.apache.sshd.sftp.server.FileHandle,
                        file: Path,
                        sshFile: String,
                        options: Set<OpenOption>,
                        vararg attrs: FileAttribute<*>,
                    ): SeekableByteChannel {
                        val delegate = SftpFileSystemAccessor.DEFAULT.openFile(proxy, handle, file, sshFile, options, *attrs)
                        return UncheckedFailingCloseChannel(delegate)
                    }
                }
            createControllableSftpServerWithAccessor(base, accessor).use { server ->
                server.start()
                Files.createDirectories(server.home.resolve("upload"))
                assertThrows<IOException> {
                    withAuthenticatedSftp(server) { sftp ->
                        sftp.write("upload/unchecked-close.bin").use { output ->
                            output.write(byteArrayOf(1, 2, 3))
                        }
                    }
                }
                assertEquals(0, server.completedWriteCount("upload/unchecked-close.bin"))
                awaitHandleDrain(server, 0)
            }
        }
    }

    @Test
    fun `close unblocks an armed write failure gate and drains handles`() {
        Files.createTempDirectory("muse-controllable-sftp-close-gate-").use { base ->
            val server = ControllableSftpServer(base)
            server.start()
            server.armWriteFailure(afterBytes = 65_536L)
            Files.createDirectories(server.home.resolve("upload"))

            val payload = ByteArray(1024 * 1024) { it.toByte() }
            val writer = Executors.newSingleThreadExecutor()
            try {
                val writeFailure =
                    writer.submit {
                        withAuthenticatedSftp(server) { sftp ->
                            sftp.write("upload/close-gate.bin").use { output ->
                                var offset = 0
                                while (offset < payload.size) {
                                    val chunk = minOf(WRITE_CHUNK_SIZE, payload.size - offset)
                                    output.write(payload, offset, chunk)
                                    offset += chunk
                                }
                            }
                        }
                    }

                server.awaitWriteFailureReady(Duration.ofSeconds(10))
                server.close()
                assertEquals(0, server.activeSessionCount)
                val failure = assertThrows<ExecutionException> {
                    writeFailure.get(10, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is IOException)
                writer.shutdownNow()
                assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS))
                assertEquals(0, server.openHandleCount)
            } finally {
                writer.shutdownNow()
                server.close()
            }
        }
    }

    @Test
    fun `constructor and start match embedded fixture semantics`() {
        Files.createTempDirectory("muse-controllable-sftp-compat-").use { base ->
            ControllableSftpServer(base).use { server ->
                server.start()
                assertTrue(server.port > 0)
                assertTrue(Files.isDirectory(server.home))
            }
        }
    }

    private fun runFirstFaultedWriteWithRelease(
        server: ControllableSftpServer,
        payload: ByteArray,
        remotePath: String,
    ): WriteObservation {
        val writer = Executors.newSingleThreadExecutor()
        try {
            val writeFailure =
                writer.submit {
                    withAuthenticatedSftp(server) { sftp ->
                        writeUntilFault(sftp, remotePath, payload)
                    }
                }
            val observation = server.awaitWriteFailureReady(Duration.ofSeconds(10))
            server.releaseWriteFailure()
            val failure = assertThrows<ExecutionException> {
                writeFailure.get(10, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is IOException)
            awaitHandleDrain(server, 0)
            return observation
        } finally {
            writer.shutdownNow()
        }
    }

    private fun runSubsequentFaultedWriteWithoutRelease(
        server: ControllableSftpServer,
        payload: ByteArray,
        remotePath: String,
    ) {
        val writer = Executors.newSingleThreadExecutor()
        try {
            val writeFailure =
                writer.submit {
                    withAuthenticatedSftp(server) { sftp ->
                        writeUntilFault(sftp, remotePath, payload)
                    }
                }
            val failure = assertThrows<ExecutionException> {
                writeFailure.get(10, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is IOException)
            awaitHandleDrain(server, 0)
        } finally {
            writer.shutdownNow()
        }
    }

    private fun writeUntilFault(
        sftp: org.apache.sshd.sftp.client.SftpClient,
        remotePath: String,
        payload: ByteArray,
    ) {
        sftp.write(remotePath).use { output ->
            var offset = 0
            while (offset < payload.size) {
                val chunk = minOf(WRITE_CHUNK_SIZE, payload.size - offset)
                output.write(payload, offset, chunk)
                offset += chunk
            }
        }
    }

    private inline fun <T> withAuthenticatedSftp(
        server: ControllableSftpServer,
        block: (org.apache.sshd.sftp.client.SftpClient) -> T,
    ): T {
        val client = SshClient.setUpDefaultClient()
        client.start()
        try {
            val session = openSession(client, server)
            try {
                return SftpClientFactory.instance().createSftpClient(session).use(block)
            } finally {
                session.close()
            }
        } finally {
            client.stop()
        }
    }

    private fun awaitWriteFailureGateWaiters(
        server: ControllableSftpServer,
        minimum: Int,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (server.activeWriteFailureGateWaiters() >= minimum) return
            Thread.onSpinWait()
        }
        error(
            "Write failure gate waiters did not reach $minimum; " +
                "active=${server.activeWriteFailureGateWaiters()}",
        )
    }

    private fun installPausingPendingReleaseGateQueue(server: ControllableSftpServer) {
        val controllerField = ControllableSftpServer::class.java.getDeclaredField("faultController")
        controllerField.isAccessible = true
        val controller = controllerField.get(server)
        val queueField = controller.javaClass.getDeclaredField("pendingReleaseGates")
        queueField.isAccessible = true
        queueField.set(controller, PausingPendingReleaseGateQueue())
    }

    private fun pendingReleaseGateQueue(server: ControllableSftpServer): java.util.Queue<CountDownLatch> {
        val controllerField = ControllableSftpServer::class.java.getDeclaredField("faultController")
        controllerField.isAccessible = true
        val controller = controllerField.get(server)
        val queueField = controller.javaClass.getDeclaredField("pendingReleaseGates")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return queueField.get(controller) as java.util.Queue<CountDownLatch>
    }

    private fun awaitThreadBlocked(
        thread: Thread?,
        timeout: Duration,
    ) {
        requireNotNull(thread) { "releaser thread was not started" }
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.BLOCKED) return
            Thread.onSpinWait()
        }
        error("Thread ${thread.name} did not enter BLOCKED within ${timeout.toMillis()}ms; state=${thread.state}")
    }

    private fun awaitPendingReleaseGateCount(
        server: ControllableSftpServer,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (pendingReleaseGateQueue(server).size == expected) return
            Thread.onSpinWait()
        }
        error(
            "Pending release gate count did not reach $expected; " +
                "size=${pendingReleaseGateQueue(server).size}",
        )
    }

    private class PausingPendingReleaseGateQueue : java.util.concurrent.ConcurrentLinkedQueue<CountDownLatch>() {
        private val additions = java.util.concurrent.atomic.AtomicInteger(0)
        val beforeSecondAdd = CountDownLatch(1)
        val allowSecondAdd = CountDownLatch(1)
        val secondAdded = CountDownLatch(1)

        override fun add(element: CountDownLatch): Boolean {
            if (additions.incrementAndGet() == 2) {
                beforeSecondAdd.countDown()
                check(allowSecondAdd.await(5, TimeUnit.SECONDS)) { "probe barrier timed out" }
                val added = super.add(element)
                secondAdded.countDown()
                return added
            }
            return super.add(element)
        }
    }

    private fun awaitHandleDrain(
        server: ControllableSftpServer,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (server.openHandleCount == expected) return
            Thread.sleep(25)
        }
        error("SFTP handles did not drain; openHandleCount=${server.openHandleCount}")
    }

    private fun openSession(
        client: SshClient,
        server: ControllableSftpServer,
    ): ClientSession {
        val session =
            client
                .connect(EmbeddedSftpServer.USERNAME, EmbeddedSftpServer.HOST, server.port)
                .verify(Duration.ofSeconds(5))
                .session
        session.addPasswordIdentity(EmbeddedSftpServer.PASSWORD)
        session.auth().verify(Duration.ofSeconds(5))
        return session
    }

    private inline fun <T> java.nio.file.Path.use(block: (java.nio.file.Path) -> T): T =
        try {
            block(this)
        } finally {
            toFile().deleteRecursively()
        }

    private class FailingCloseChannel(
        private val delegate: SeekableByteChannel,
    ) : SeekableByteChannel by delegate {
        override fun close() {
            delegate.close()
            throw IOException("Injected close failure for test")
        }

        override fun write(src: ByteBuffer): Int = delegate.write(src)
    }

    private class UncheckedFailingCloseChannel(
        private val delegate: SeekableByteChannel,
    ) : SeekableByteChannel by delegate {
        override fun close() {
            delegate.close()
            throw IllegalStateException("Injected unchecked close failure for test")
        }

        override fun write(src: ByteBuffer): Int = delegate.write(src)
    }

    private class ErrorFailingCloseChannel(
        private val delegate: SeekableByteChannel,
        private val injectedClose: CountDownLatch,
    ) : SeekableByteChannel by delegate {
        override fun close() {
            delegate.close()
            injectedClose.countDown()
            throw AssertionError("Injected error close failure for test")
        }

        override fun write(src: ByteBuffer): Int = delegate.write(src)
    }

    private companion object {
        const val WRITE_CHUNK_SIZE = 32 * 1024
    }
}

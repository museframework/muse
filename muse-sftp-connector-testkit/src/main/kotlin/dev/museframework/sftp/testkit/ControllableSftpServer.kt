package dev.museframework.sftp.testkit

import org.apache.sshd.server.SshServer
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class WriteObservation(
    val remotePath: String,
    val acceptedBytes: Long,
)

class ControllableSftpServer(
    baseDirectory: Path,
    requestedPort: Int = 0,
    sftpFileSystemAccessorDelegate: SftpFileSystemAccessor = SftpFileSystemAccessor.DEFAULT,
) : AutoCloseable {
    init {
        require(requestedPort in 0..65_535) {
            "requestedPort must be in 0..65535 but was $requestedPort"
        }
    }

    private val faultController = WriteFailureController()
    private val openHandles = AtomicInteger(0)
    private val completedWrites = ConcurrentHashMap<String, AtomicInteger>()
    private val listenerHome = Files.createDirectories(baseDirectory.resolve("home"))
    private val fileSystemAccessor =
        ControllableSftpFileSystemAccessor(
            home = listenerHome,
            faultController = faultController,
            openHandles = openHandles,
            completedWrites = completedWrites,
            delegate = sftpFileSystemAccessorDelegate,
        )
    private val sftpFactory =
        SftpSubsystemFactory.Builder()
            .withFileSystemAccessor(fileSystemAccessor)
            .build()
    private val assembly =
        assembleSftpTestkitServer(
            baseDirectory = baseDirectory,
            requestedPort = requestedPort,
            sftpSubsystemFactory = sftpFactory,
        )
    private val server: SshServer = assembly.server
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val home: Path = assembly.home

    val port: Int
        get() = server.port

    val activeSessionCount: Int
        get() {
            if (closed.get() || !started.get()) return 0
            return runCatching { server.activeSessions.size }.getOrDefault(0)
        }

    val openHandleCount: Int
        get() = openHandles.get()

    fun start() {
        check(!closed.get()) { "Controllable SFTP server is closed" }
        check(started.compareAndSet(false, true)) { "Controllable SFTP server is already started" }
        try {
            server.start()
        } catch (failure: Throwable) {
            started.set(false)
            throw failure
        }
    }

    fun armWriteFailure(afterBytes: Long) {
        require(afterBytes >= 0L) { "afterBytes must be non-negative but was $afterBytes" }
        faultController.arm(afterBytes)
    }

    fun awaitWriteFailureReady(timeout: Duration): WriteObservation =
        faultController.awaitReady(timeout, closed)

    fun releaseWriteFailure() {
        faultController.releaseGate()
    }

    fun clearWriteFailure() {
        faultController.clear()
    }

    fun activeWriteFailureGateWaiters(): Int = faultController.activeGateWaiterCount()

    fun completedWriteCount(remotePath: String): Int =
        completedWrites[remotePath]?.get() ?: 0

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            faultController.shutdown()
            if (started.compareAndSet(true, false)) {
                server.stop(true)
            }
        }
    }
}

private class TransferWriteState {
    val acceptedBytes = AtomicLong(0L)
    val faultSignaled = AtomicBoolean(false)
}

private class WriteFailureController {
    private val armed = AtomicBoolean(false)
    private val afterBytes = AtomicLong(-1L)
    private val transferStates = ConcurrentHashMap<String, TransferWriteState>()
    private val gateReleasedForGeneration = AtomicBoolean(false)
    private val pendingReleaseGates = ConcurrentLinkedQueue<CountDownLatch>()
    private val readyObservation = AtomicReference<WriteObservation?>(null)
    private val readyGate = AtomicReference(CountDownLatch(1))
    private val shutdown = AtomicBoolean(false)
    private val writeLock = Any()
    private val gateLock = Any()
    private val activeGateWaiters = AtomicInteger(0)
    private val gateAwaitTimeout = Duration.ofMinutes(5)

    fun activeGateWaiterCount(): Int = activeGateWaiters.get()

    fun registerTransfer(transferId: String) {
        transferStates[transferId] = TransferWriteState()
    }

    fun unregisterTransfer(transferId: String) {
        transferStates.remove(transferId)
    }

    fun arm(afterBytes: Long) {
        synchronized(gateLock) {
            this.afterBytes.set(afterBytes)
            transferStates.clear()
            readyObservation.set(null)
            gateReleasedForGeneration.set(false)
            releaseAllPendingGates()
            readyGate.set(CountDownLatch(1))
            armed.set(true)
        }
    }

    fun clear() {
        synchronized(gateLock) {
            armed.set(false)
            afterBytes.set(-1L)
            transferStates.clear()
            readyObservation.set(null)
            gateReleasedForGeneration.set(true)
            releaseAllPendingGates()
            signalReadyGate()
        }
    }

    fun write(
        transferId: String,
        remotePath: String,
        source: ByteBuffer,
        write: (ByteBuffer) -> Int,
    ): Int {
        val outcome =
            synchronized(writeLock) {
                if (!armed.get() || shutdown.get()) {
                    val written = write(source)
                    if (written > 0) {
                        transferStates[transferId]?.acceptedBytes?.addAndGet(written.toLong())
                    }
                    return@synchronized WriteOutcome.Written(written)
                }
                val threshold = afterBytes.get()
                if (threshold < 0) {
                    return@synchronized WriteOutcome.Written(write(source))
                }
                val transfer = transferStates[transferId] ?: TransferWriteState().also { transferStates[transferId] = it }
                if (transfer.faultSignaled.get()) {
                    return@synchronized WriteOutcome.FailNow(
                        "Injected SFTP write failure remains armed on '$remotePath'",
                    )
                }
                val accepted = transfer.acceptedBytes.get()
                if (accepted >= threshold) {
                    transfer.faultSignaled.set(true)
                    return@synchronized WriteOutcome.Fault(remotePath, accepted)
                }
                val allowance = threshold - accepted
                if (source.remaining().toLong() > allowance) {
                    val prefixLength = allowance.toInt()
                    if (prefixLength > 0) {
                        val prefix = source.slice()
                        prefix.limit(prefix.position() + prefixLength)
                        write(prefix)
                        source.position(source.position() + prefixLength)
                        transfer.acceptedBytes.addAndGet(allowance)
                    }
                    transfer.faultSignaled.set(true)
                    return@synchronized WriteOutcome.Fault(remotePath, threshold)
                }
                val written = write(source)
                if (written > 0) {
                    transfer.acceptedBytes.addAndGet(written.toLong())
                }
                WriteOutcome.Written(written)
            }
        return when (outcome) {
            is WriteOutcome.Written -> outcome.bytes
            is WriteOutcome.FailNow -> throw IOException(outcome.message)
            is WriteOutcome.Fault -> pauseThenFail(outcome.remotePath, outcome.acceptedBytes)
        }
    }

    private sealed interface WriteOutcome {
        data class Written(val bytes: Int) : WriteOutcome

        data class FailNow(val message: String) : WriteOutcome

        data class Fault(
            val remotePath: String,
            val acceptedBytes: Long,
        ) : WriteOutcome
    }

    private fun pauseThenFail(
        remotePath: String,
        acceptedBytes: Long,
    ): Nothing {
        val releaseLatch =
            synchronized(gateLock) {
                signalFaultReady(remotePath, acceptedBytes)
                if (gateReleasedForGeneration.get()) {
                    null
                } else {
                    CountDownLatch(1).also { latch ->
                        pendingReleaseGates.add(latch)
                        activeGateWaiters.incrementAndGet()
                    }
                }
            }
        try {
            releaseLatch?.let { awaitGateWithTimeout(it) }
            if (shutdown.get()) {
                throw IOException("Controllable SFTP server closed during injected write failure")
            }
            throw IOException("Injected SFTP write failure after $acceptedBytes accepted bytes on '$remotePath'")
        } finally {
            if (releaseLatch != null) {
                activeGateWaiters.decrementAndGet()
            }
        }
    }

    private fun signalFaultReady(
        remotePath: String,
        acceptedBytes: Long,
    ) {
        val observation =
            WriteObservation(
                remotePath = remotePath,
                acceptedBytes = acceptedBytes,
            )
        readyObservation.compareAndSet(null, observation)
        signalReadyGate()
    }

    fun awaitReady(
        timeout: Duration,
        closed: AtomicBoolean,
    ): WriteObservation {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (closed.get()) {
                throw IllegalStateException("Controllable SFTP server is closed")
            }
            readyObservation.get()?.let { observation ->
                readyObservation.set(null)
                readyGate.set(CountDownLatch(1))
                return observation
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) break
            readyGate.get().await(minOf(remainingNanos, 50_000_000L), TimeUnit.NANOSECONDS)
        }
        error("Timed out waiting for write failure observation after ${timeout.toMillis()}ms")
    }

    fun releaseGate() {
        synchronized(gateLock) {
            gateReleasedForGeneration.set(true)
            releaseAllPendingGates()
        }
    }

    fun shutdown() {
        synchronized(gateLock) {
            shutdown.set(true)
            gateReleasedForGeneration.set(true)
            releaseAllPendingGates()
            signalReadyGate()
        }
    }

    private fun releaseAllPendingGates() {
        while (true) {
            pendingReleaseGates.poll()?.countDown() ?: break
        }
    }

    private fun awaitGateWithTimeout(releaseLatch: CountDownLatch) {
        val deadline = System.nanoTime() + gateAwaitTimeout.toNanos()
        while (!shutdown.get()) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) {
                throw IOException("Timed out waiting for write failure release after ${gateAwaitTimeout.toMillis()}ms")
            }
            try {
                if (releaseLatch.await(minOf(remainingNanos, 100_000_000L), TimeUnit.NANOSECONDS)) return
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for write failure release")
            }
        }
    }

    private fun signalReadyGate() {
        readyGate.get().countDown()
    }
}

private class ControllableSftpFileSystemAccessor(
    private val home: Path,
    private val faultController: WriteFailureController,
    private val openHandles: AtomicInteger,
    private val completedWrites: ConcurrentHashMap<String, AtomicInteger>,
    private val delegate: SftpFileSystemAccessor = SftpFileSystemAccessor.DEFAULT,
) : SftpFileSystemAccessor by delegate {
    override fun openFile(
        proxy: SftpSubsystemProxy,
        handle: FileHandle,
        file: Path,
        sshFile: String,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel {
        val channel = delegate.openFile(proxy, handle, file, sshFile, options, *attrs)
        val remotePath = file.remotePath(home)
        val transferId = handle.fileHandle
        val writeHandle =
            StandardOpenOption.WRITE in options ||
                StandardOpenOption.CREATE in options ||
                StandardOpenOption.APPEND in options
        openHandles.incrementAndGet()
        if (!writeHandle) {
            return HandleTrackingChannel(channel) { recordHandleClose(countCompletedWrite = false, failed = false) }
        }
        faultController.registerTransfer(transferId)
        return FaultableWriteChannel(
            transferId = transferId,
            delegate = channel,
            remotePath = remotePath,
            faultController = faultController,
            onClose = { failed ->
                faultController.unregisterTransfer(transferId)
                recordHandleClose(countCompletedWrite = true, failed = failed, remotePath = remotePath)
            },
        )
    }

    override fun closeFile(
        proxy: SftpSubsystemProxy,
        handle: FileHandle,
        file: Path,
        sshFile: String,
        channel: java.nio.channels.Channel,
        options: Set<OpenOption>,
    ) {
        try {
            delegate.closeFile(proxy, handle, file, sshFile, channel, options)
        } finally {
            when (channel) {
                is FaultableWriteChannel -> channel.finishFromSubsystemClose()
                is HandleTrackingChannel -> channel.finishFromSubsystemClose()
            }
        }
    }

    private fun recordHandleClose(
        countCompletedWrite: Boolean,
        failed: Boolean,
        remotePath: String = "",
    ) {
        openHandles.decrementAndGet()
        if (countCompletedWrite && !failed && remotePath.isNotEmpty()) {
            completedWrites
                .computeIfAbsent(remotePath) { AtomicInteger(0) }
                .incrementAndGet()
        }
    }
}

private fun Path.remotePath(home: Path): String =
    try {
        home.relativize(this).toString().replace('\\', '/')
    } catch (_: IllegalArgumentException) {
        toString().removePrefix("/")
    }

private class HandleTrackingChannel(
    private val delegate: SeekableByteChannel,
    private val onClose: () -> Unit,
) : SeekableByteChannel by delegate {
    private val closed = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close()
            finishFromSubsystemClose()
        }
    }

    fun finishFromSubsystemClose() {
        if (finished.compareAndSet(false, true)) {
            onClose()
        }
    }
}

private class FaultableWriteChannel(
    private val transferId: String,
    private val delegate: SeekableByteChannel,
    private val remotePath: String,
    private val faultController: WriteFailureController,
    private val onClose: (failed: Boolean) -> Unit,
) : SeekableByteChannel {
    private val closed = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private var failed = false

    override fun write(src: ByteBuffer): Int =
        try {
            faultController.write(transferId, remotePath, src) { buffer -> delegate.write(buffer) }
        } catch (failure: Exception) {
            failed = true
            when (failure) {
                is IOException -> throw failure
                else -> throw IOException(failure)
            }
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            var closeReturnedNormally = false
            try {
                delegate.close()
                closeReturnedNormally = true
            } catch (failure: Throwable) {
                failed = true
                when (failure) {
                    is IOException -> throw failure
                    is Exception -> throw IOException(failure)
                    else -> throw failure
                }
            } finally {
                if (!closeReturnedNormally) {
                    failed = true
                }
                finishFromSubsystemClose()
            }
        }
    }

    fun finishFromSubsystemClose() {
        if (finished.compareAndSet(false, true)) {
            onClose(failed)
        }
    }

    override fun isOpen(): Boolean = !closed.get() && delegate.isOpen

    override fun read(dst: ByteBuffer): Int = delegate.read(dst)

    override fun position(): Long = delegate.position()

    override fun position(newPosition: Long): SeekableByteChannel = apply { delegate.position(newPosition) }

    override fun size(): Long = delegate.size()

    override fun truncate(size: Long): SeekableByteChannel = apply { delegate.truncate(size) }
}

internal fun createControllableSftpServerWithAccessor(
    baseDirectory: Path,
    delegateAccessor: SftpFileSystemAccessor,
): ControllableSftpServer =
    ControllableSftpServer(
        baseDirectory = baseDirectory,
        sftpFileSystemAccessorDelegate = delegateAccessor,
    )

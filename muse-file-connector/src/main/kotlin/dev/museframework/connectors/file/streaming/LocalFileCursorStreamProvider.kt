package dev.museframework.connectors.file.streaming

import dev.museframework.connectors.file.internal.FileConnectorException
import dev.museframework.connectors.file.internal.FileLockRegistry
import dev.museframework.connectors.file.internal.awaitStableSize
import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingCleanupException
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.api.streaming.StreamingProviderClosedException
import dev.museframework.engine.api.streaming.StreamingResourceReleasedException
import dev.museframework.file.api.model.FileErrorType
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class LocalFileCursorStreamProvider(
    private val file: Path,
    private val lock: Boolean,
    private val timeBetweenSizeCheck: Long? = null,
    private val timeBetweenSizeCheckUnit: TimeUnit = TimeUnit.MILLISECONDS,
    private val lockRegistry: FileLockRegistry? = null,
) : MuseCursorStreamProvider {
    private val closed = AtomicBoolean(false)
    internal val released = AtomicBoolean(false)
    private val cursorLock = Any()
    private val cursors = mutableSetOf<LocalFileCursorStream>()
    private val contentPrepared = AtomicBoolean(false)
    private var resolvedSize: Long = -1L
    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    private val lockHeld = AtomicBoolean(false)

    override val isClosed: Boolean
        get() = closed.get()

    override val size: Long
        get() {
            ensureContentReady()
            return resolvedSize
        }

    override fun openCursor(): MuseCursorStream =
        synchronized(cursorLock) {
            if (closed.get()) throw StreamingProviderClosedException()
            if (released.get()) throw StreamingResourceReleasedException()
            ensureContentReadyLocked()
            if (lock) acquireFileLockLocked()
            LocalFileCursorStream(this, file, resolvedSize).also(cursors::add)
        }

    override fun close() {
        releaseResources()
    }

    override fun releaseResources() {
        val snapshot =
            synchronized(cursorLock) {
                if (!released.compareAndSet(false, true)) return
                closed.set(true)
                cursors.toList().also { cursors.clear() }
            }
        val failures = mutableListOf<Throwable>()
        snapshot.forEach { cursor ->
            runCatching(cursor::release).exceptionOrNull()?.let(failures::add)
        }
        runCatching { releaseFileLock() }.exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) throw StreamingCleanupException(failures)
    }

    internal fun unregister(cursor: LocalFileCursorStream) {
        synchronized(cursorLock) {
            cursors.remove(cursor)
            if (cursors.isEmpty()) {
                releaseFileLock()
            }
        }
    }

    private fun ensureContentReady() {
        synchronized(cursorLock) {
            ensureContentReadyLocked()
        }
    }

    private fun ensureContentReadyLocked() {
        if (contentPrepared.get()) return
        awaitStableSize(file, timeBetweenSizeCheck, timeBetweenSizeCheckUnit)
        resolvedSize = Files.size(file)
        if (lock) {
            acquireFileLockLocked()
        }
        contentPrepared.set(true)
    }

    private fun acquireFileLockLocked() {
        if (lockHeld.get()) return
        var channel: FileChannel? = null
        try {
            channel = FileChannel.open(file, StandardOpenOption.READ)
            val acquired =
                channel.tryLock(0L, Long.MAX_VALUE, true)
                    ?: throw FileConnectorException(FileErrorType.FILE_LOCK, "Could not lock file '$file'")
            lockChannel = channel
            fileLock = acquired
            lockRegistry?.acquire(file)
            lockHeld.set(true)
        } catch (failure: Exception) {
            channel?.close()
            if (failure is FileConnectorException) throw failure
            throw FileConnectorException(FileErrorType.FILE_LOCK, "Could not lock file '$file'", failure)
        }
    }

    private fun releaseFileLock() {
        if (!lockHeld.compareAndSet(true, false)) return
        runCatching {
            if (fileLock?.isValid == true) fileLock?.release()
        }
        runCatching { lockChannel?.close() }
        fileLock = null
        lockChannel = null
        lockRegistry?.release(file)
    }
}

internal class LocalFileCursorStream(
    override val provider: LocalFileCursorStreamProvider,
    private val file: Path,
    private val totalSize: Long,
) : MuseCursorStream() {
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private var channel: FileChannel? = null
    private var index = 0L
    private val oneByte = ByteBuffer.allocate(1)

    override val position: Long
        get() = index

    override val isReleased: Boolean
        get() = released.get()

    override fun read(): Int {
        ensureReadable()
        if (index >= totalSize) return -1
        oneByte.clear()
        val read = fileChannel().read(oneByte, index)
        if (read < 0) return -1
        index += read
        oneByte.flip()
        return oneByte.get().toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureReadable()
        if (offset < 0 || length < 0 || length > buffer.size - offset) {
            throw IndexOutOfBoundsException("offset=$offset length=$length bufferSize=${buffer.size}")
        }
        if (length == 0) return 0
        if (index >= totalSize) return -1
        val boundedLength = minOf(length.toLong(), totalSize - index).toInt()
        val read = fileChannel().read(ByteBuffer.wrap(buffer, offset, boundedLength), index)
        if (read > 0) index += read
        return read
    }

    override fun available(): Int = minOf(totalSize - index, Int.MAX_VALUE.toLong()).coerceAtLeast(0).toInt()

    override fun seek(position: Long) {
        ensureReadable()
        require(position in 0..totalSize) { "position must be between zero and stream size" }
        index = position
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                channel?.close()
            } finally {
                provider.unregister(this)
            }
        }
    }

    override fun release() {
        released.set(true)
        closed.set(true)
        try {
            channel?.close()
        } finally {
            provider.unregister(this)
        }
    }

    private fun fileChannel(): FileChannel =
        channel ?: FileChannel.open(file, StandardOpenOption.READ).also { channel = it }

    private fun ensureReadable() {
        if (released.get() || provider.released.get()) throw StreamingResourceReleasedException()
        if (closed.get()) throw StreamingException("Cursor is closed")
    }
}

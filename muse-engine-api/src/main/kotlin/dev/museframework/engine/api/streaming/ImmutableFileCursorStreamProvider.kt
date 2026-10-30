package dev.museframework.engine.api.streaming

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

class ImmutableFileCursorStreamProvider(
    private val file: Path,
    private val deleteFileOnClose: Boolean = false,
) : MuseCursorStreamProvider {
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val cursorLock = Any()
    private val cursors = mutableSetOf<ImmutableFileCursorStream>()

    init {
        require(file.exists()) { "Immutable file does not exist: $file" }
    }

    override val isClosed: Boolean
        get() = closed.get()

    override val size: Long
        get() = Files.size(file)

    override fun openCursor(): MuseCursorStream =
        synchronized(cursorLock) {
            if (closed.get()) throw StreamingProviderClosedException()
            if (released.get()) throw StreamingResourceReleasedException()
            ImmutableFileCursorStream(this, file).also { cursors.add(it) }
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
            try {
                cursor.release()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        if (deleteFileOnClose) {
            try {
                file.deleteIfExists()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        if (failures.isNotEmpty()) throw StreamingCleanupException(failures)
    }

    private fun unregister(cursor: ImmutableFileCursorStream) {
        synchronized(cursorLock) { cursors.remove(cursor) }
    }

    private class ImmutableFileCursorStream(
        override val provider: ImmutableFileCursorStreamProvider,
        private val file: Path,
    ) : MuseCursorStream() {
        private val closed = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private var fileChannel: java.nio.channels.SeekableByteChannel? = null
        private var index = 0L
        private val totalSize: Long = Files.size(file)

        override val position: Long
            get() = index

        override val isReleased: Boolean
            get() = released.get()

        override fun read(): Int {
            ensureReadable()
            if (index >= totalSize) return -1
            val buffer = java.nio.ByteBuffer.allocate(1)
            val channel = fileChannel()
            channel.position(index)
            val read = channel.read(buffer)
            if (read == -1) return -1
            index += read.toLong()
            buffer.flip()
            return buffer.get().toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            ensureReadable()
            if (offset < 0 || length < 0 || length > buffer.size - offset) {
                throw IndexOutOfBoundsException("offset=$offset length=$length bufferSize=${buffer.size}")
            }
            if (length == 0) return 0
            if (index >= totalSize) return -1
            val channel = fileChannel()
            channel.position(index)
            val read = channel.read(java.nio.ByteBuffer.wrap(buffer, offset, length))
            if (read > 0) index += read.toLong()
            return read
        }

        override fun seek(position: Long) {
            ensureReadable()
            require(position >= 0) { "position must be >= 0" }
            require(position <= totalSize) { "position must be <= stream size" }
            index = position
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    fileChannel?.close()
                } finally {
                    provider.unregister(this)
                }
            }
        }

        override fun release() {
            released.set(true)
            closed.set(true)
            try {
                fileChannel?.close()
            } finally {
                provider.unregister(this)
            }
        }

        private fun fileChannel(): java.nio.channels.SeekableByteChannel =
            fileChannel ?: Files.newByteChannel(file, java.nio.file.StandardOpenOption.READ).also { fileChannel = it }

        private fun ensureReadable() {
            if (released.get() || provider.released.get()) throw StreamingResourceReleasedException()
            if (closed.get()) throw StreamingException("Cursor is closed")
        }
    }
}

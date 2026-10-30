package dev.museframework.engine.api.streaming

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class RepeatableInMemoryCursorStreamProvider private constructor(
    private val bytes: ByteArray,
) : MuseCursorStreamProvider {
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val cursorLock = Any()
    private val cursors = mutableSetOf<InMemoryCursorStream>()

    override val isClosed: Boolean
        get() = closed.get()

    override val size: Long
        get() = bytes.size.toLong()

    override fun openCursor(): MuseCursorStream =
        synchronized(cursorLock) {
            if (closed.get()) throw StreamingProviderClosedException()
            if (released.get()) throw StreamingResourceReleasedException()
            InMemoryCursorStream(this, bytes).also { cursors.add(it) }
        }

    override fun close() {
        synchronized(cursorLock) { closed.set(true) }
    }

    override fun releaseResources() {
        val snapshot =
            synchronized(cursorLock) {
                if (!released.compareAndSet(false, true)) return
                closed.set(true)
                cursors.toList().also { cursors.clear() }
            }
        snapshot.forEach { it.release() }
    }

    private fun unregister(cursor: InMemoryCursorStream) {
        synchronized(cursorLock) { cursors.remove(cursor) }
    }

    private class InMemoryCursorStream(
        override val provider: RepeatableInMemoryCursorStreamProvider,
        private val bytes: ByteArray,
    ) : MuseCursorStream() {
        private val closed = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private var index = 0

        override val position: Long
            get() = index.toLong()

        override val isReleased: Boolean
            get() = released.get()

        override fun read(): Int {
            ensureReadable()
            if (index >= bytes.size) return -1
            return bytes[index++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            ensureReadable()
            if (offset < 0 || length < 0 || length > buffer.size - offset) {
                throw IndexOutOfBoundsException("offset=$offset length=$length bufferSize=${buffer.size}")
            }
            if (length == 0) return 0
            if (index >= bytes.size) return -1
            val count = minOf(length, bytes.size - index)
            bytes.copyInto(buffer, offset, index, index + count)
            index += count
            return count
        }

        override fun available(): Int {
            ensureReadable()
            return bytes.size - index
        }

        override fun seek(position: Long) {
            ensureReadable()
            require(position >= 0) { "position must be >= 0" }
            require(position <= bytes.size) { "position must be <= stream size" }
            index = position.toInt()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                provider.unregister(this)
            }
        }

        override fun release() {
            released.set(true)
            closed.set(true)
            provider.unregister(this)
        }

        private fun ensureReadable() {
            if (released.get() || provider.released.get()) throw StreamingResourceReleasedException()
            if (closed.get()) throw StreamingException("Cursor is closed")
        }
    }

    companion object {
        fun fromBytes(bytes: ByteArray): RepeatableInMemoryCursorStreamProvider =
            RepeatableInMemoryCursorStreamProvider(bytes.copyOf())

        fun fromInputStream(
            inputStream: InputStream,
            initialBufferSizeBytes: Int = 512 * 1024,
            bufferSizeIncrementBytes: Int = 512 * 1024,
            maxBufferSizeBytes: Int = 1024 * 1024,
        ): RepeatableInMemoryCursorStreamProvider {
            require(initialBufferSizeBytes > 0) { "initialBufferSizeBytes must be > 0" }
            require(bufferSizeIncrementBytes > 0) { "bufferSizeIncrementBytes must be > 0" }
            require(maxBufferSizeBytes >= 0) { "maxBufferSizeBytes must be >= 0" }
            val effectiveLimit = if (maxBufferSizeBytes == 0) Int.MAX_VALUE else maxBufferSizeBytes
            var storage = ByteArray(minOf(initialBufferSizeBytes, effectiveLimit))
            val buffer = ByteArray(8192)
            var size = 0
            inputStream.use { source ->
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    val requiredSize = size.toLong() + read.toLong()
                    if (requiredSize > effectiveLimit.toLong()) {
                        throw StreamMaximumSizeExceededException(effectiveLimit.toLong(), requiredSize)
                    }
                    if (requiredSize > storage.size.toLong()) {
                        val missing = requiredSize - storage.size.toLong()
                        val increments = (missing + bufferSizeIncrementBytes - 1L) / bufferSizeIncrementBytes
                        val grownSize = storage.size.toLong() + increments * bufferSizeIncrementBytes.toLong()
                        storage = storage.copyOf(minOf(grownSize, effectiveLimit.toLong()).toInt())
                    }
                    buffer.copyInto(storage, destinationOffset = size, endIndex = read)
                    size += read
                }
            }
            return RepeatableInMemoryCursorStreamProvider(storage.copyOf(size))
        }
    }
}

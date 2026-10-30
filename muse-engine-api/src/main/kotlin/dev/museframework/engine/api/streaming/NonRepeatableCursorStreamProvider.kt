package dev.museframework.engine.api.streaming

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class NonRepeatableCursorStreamProvider(
    private val inputStream: InputStream,
) : MuseCursorStreamProvider {
    private val opened = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    internal val released = AtomicBoolean(false)
    private val cursorLock = Any()
    private val cursors = mutableSetOf<NonRepeatableCursorStream>()

    override val isClosed: Boolean
        get() = closed.get()

    override fun openCursor(): MuseCursorStream =
        synchronized(cursorLock) {
            if (closed.get()) throw StreamingProviderClosedException()
            if (released.get()) throw StreamingResourceReleasedException()
            check(opened.compareAndSet(false, true)) {
                "Non-repeatable stream has already been consumed"
            }
            NonRepeatableCursorStream(this, inputStream).also { cursors.add(it) }
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
        inputStream.close()
    }

    internal fun unregister(cursor: MuseCursorStream) {
        synchronized(cursorLock) { cursors.remove(cursor) }
    }
}

private class NonRepeatableCursorStream(
    override val provider: NonRepeatableCursorStreamProvider,
    private val delegate: InputStream,
) : MuseCursorStream() {
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private var bytesRead = 0L

    override val position: Long
        get() = bytesRead

    override val isReleased: Boolean
        get() = released.get()

    override fun read(): Int {
        ensureReadable()
        val result = delegate.read()
        if (result != -1) bytesRead++
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureReadable()
        if (offset < 0 || length < 0 || length > buffer.size - offset) {
            throw IndexOutOfBoundsException("offset=$offset length=$length bufferSize=${buffer.size}")
        }
        if (length == 0) return 0
        val result = delegate.read(buffer, offset, length)
        if (result > 0) bytesRead += result
        return result
    }

    override fun available(): Int {
        ensureReadable()
        return delegate.available()
    }

    override fun seek(position: Long) {
        ensureReadable()
        require(position >= bytesRead) { "Non-repeatable stream only supports forward seek" }
        var remaining = position - bytesRead
        while (remaining > 0L) {
            val skipped = delegate.skip(remaining)
            if (skipped > 0L) {
                bytesRead += skipped
                remaining -= skipped
            } else {
                if (delegate.read() == -1) return
                bytesRead++
                remaining--
            }
        }
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

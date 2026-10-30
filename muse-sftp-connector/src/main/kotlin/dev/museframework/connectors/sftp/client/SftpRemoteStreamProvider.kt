package dev.museframework.connectors.sftp.client

import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.api.streaming.StreamingProviderClosedException
import dev.museframework.engine.api.streaming.StreamingResourceReleasedException
import java.util.concurrent.atomic.AtomicBoolean

internal class SftpRemoteStreamProvider(
    private val client: MuseSftpClient,
    private val path: String,
    override val size: Long,
    private val nonRepeatable: Boolean = false,
) : MuseCursorStreamProvider {
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val cursors = mutableSetOf<SftpRemoteCursor>()
    private var hasOpened = false

    override val isClosed: Boolean
        get() = closed.get()

    override fun openCursor(): MuseCursorStream =
        synchronized(cursors) {
            if (closed.get()) throw StreamingProviderClosedException()
            if (released.get()) throw StreamingResourceReleasedException()
            if (nonRepeatable && hasOpened) {
                throw StreamingException("Non-repeatable SFTP stream has already been consumed")
            }
            val cursor = SftpRemoteCursor(this, client.openRead(path))
            hasOpened = true
            cursors += cursor
            cursor
        }

    override fun close() {
        closed.set(true)
    }

    override fun releaseResources() {
        val snapshot =
            synchronized(cursors) {
                if (!released.compareAndSet(false, true)) return
                closed.set(true)
                cursors.toList().also { cursors.clear() }
            }
        snapshot.forEach(SftpRemoteCursor::release)
    }

    internal fun unregister(cursor: SftpRemoteCursor) {
        synchronized(cursors) { cursors.remove(cursor) }
    }
}

internal class SftpRemoteCursor(
    override val provider: SftpRemoteStreamProvider,
    private val handle: SftpReadHandle,
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
        return handle.inputStream.read().also { if (it != -1) bytesRead++ }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        ensureReadable()
        return handle.inputStream.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
    }

    override fun available(): Int {
        ensureReadable()
        return handle.inputStream.available()
    }

    override fun seek(position: Long) {
        ensureReadable()
        require(position >= bytesRead) { "SFTP cursor only supports forward seek" }
        var remaining = position - bytesRead
        while (remaining > 0) {
            val skipped = handle.inputStream.skip(remaining)
            if (skipped > 0) {
                bytesRead += skipped
                remaining -= skipped
            } else {
                if (handle.inputStream.read() == -1) return
                bytesRead++
                remaining--
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            handle.close()
            provider.unregister(this)
        }
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            closed.set(true)
            handle.close()
            provider.unregister(this)
        }
    }

    private fun ensureReadable() {
        if (released.get()) throw StreamingResourceReleasedException()
        if (closed.get()) throw StreamingException("Cursor is closed")
    }
}

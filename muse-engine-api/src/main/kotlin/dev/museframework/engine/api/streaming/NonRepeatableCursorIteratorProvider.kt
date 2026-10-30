package dev.museframework.engine.api.streaming

import java.util.concurrent.atomic.AtomicBoolean

class NonRepeatableCursorIteratorProvider<T>(
    private val iterator: Iterator<T>,
) : MuseCursorIteratorProvider<T> {
    private val opened = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val cursorLock = Any()
    private val cursors = mutableSetOf<NonRepeatableCursorIterator<T>>()

    override val isClosed: Boolean
        get() = closed.get()

    override fun openCursor(): MuseCursorIterator<T> =
        synchronized(cursorLock) {
            if (closed.get()) throw StreamingProviderClosedException()
            if (released.get()) throw StreamingResourceReleasedException()
            check(opened.compareAndSet(false, true)) {
                "Non-repeatable iterator has already been consumed"
            }
            NonRepeatableCursorIterator(iterator, this).also { cursors.add(it) }
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
        (iterator as? AutoCloseable)?.close()
    }

    private fun unregister(cursor: NonRepeatableCursorIterator<T>) {
        synchronized(cursorLock) { cursors.remove(cursor) }
    }

    private class NonRepeatableCursorIterator<T>(
        private val delegate: Iterator<T>,
        override val provider: NonRepeatableCursorIteratorProvider<T>,
    ) : MuseCursorIterator<T> {
        private val closed = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private var itemsRead = 0L

        override val position: Long
            get() = itemsRead

        override val isReleased: Boolean
            get() = released.get()

        override fun hasNext(): Boolean {
            ensureReadable()
            return delegate.hasNext()
        }

        override fun next(): T {
            ensureReadable()
            val result = delegate.next()
            itemsRead++
            return result
        }

        override fun seek(position: Long) {
            ensureReadable()
            require(position >= itemsRead) { "Non-repeatable iterator only supports forward seek" }
            while (itemsRead < position && delegate.hasNext()) {
                delegate.next()
                itemsRead++
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
}

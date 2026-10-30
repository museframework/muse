package dev.museframework.engine.api.streaming

import java.util.concurrent.atomic.AtomicBoolean

class RepeatableInMemoryCursorIteratorProvider<T> private constructor(
    private val items: List<T>,
) : MuseCursorIteratorProvider<T> {
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val cursorLock = Any()
    private val cursors = mutableSetOf<InMemoryCursorIterator<T>>()

    override val isClosed: Boolean
        get() = closed.get()

    override val size: Long
        get() = items.size.toLong()

    override fun openCursor(): MuseCursorIterator<T> =
        synchronized(cursorLock) {
            if (closed.get()) throw StreamingProviderClosedException()
            if (released.get()) throw StreamingResourceReleasedException()
            InMemoryCursorIterator(this, items).also { cursors.add(it) }
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

    private fun unregister(cursor: InMemoryCursorIterator<T>) {
        synchronized(cursorLock) { cursors.remove(cursor) }
    }

    private class InMemoryCursorIterator<T>(
        override val provider: RepeatableInMemoryCursorIteratorProvider<T>,
        private val items: List<T>,
    ) : MuseCursorIterator<T> {
        private val closed = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private var index = 0

        override val position: Long
            get() = index.toLong()

        override val isReleased: Boolean
            get() = released.get()

        override val size: Long
            get() = items.size.toLong()

        override fun hasNext(): Boolean {
            ensureReadable()
            return index < items.size
        }

        override fun next(): T {
            ensureReadable()
            if (index >= items.size) throw NoSuchElementException()
            return items[index++]
        }

        override fun seek(position: Long) {
            ensureReadable()
            require(position >= 0) { "position must be >= 0" }
            require(position <= items.size) { "position must be <= iterator size" }
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
        fun <T> fromIterable(
            iterable: Iterable<T>,
            initialObjectBufferSize: Int = 500,
            objectBufferSizeIncrement: Int = 100,
            maxObjectBufferSize: Int = 1000,
        ): RepeatableInMemoryCursorIteratorProvider<T> {
            require(initialObjectBufferSize > 0) { "initialObjectBufferSize must be > 0" }
            require(objectBufferSizeIncrement > 0) { "objectBufferSizeIncrement must be > 0" }
            require(maxObjectBufferSize >= 0) { "maxObjectBufferSize must be >= 0" }
            val effectiveLimit = if (maxObjectBufferSize == 0) Int.MAX_VALUE else maxObjectBufferSize
            var capacity = minOf(initialObjectBufferSize, effectiveLimit)
            val items = ArrayList<T>(capacity)
            for (item in iterable) {
                val requiredSize = items.size.toLong() + 1L
                if (requiredSize > effectiveLimit.toLong()) {
                    throw StreamMaximumSizeExceededException(effectiveLimit.toLong(), requiredSize)
                }
                if (requiredSize > capacity.toLong()) {
                    val missing = requiredSize - capacity.toLong()
                    val increments = (missing + objectBufferSizeIncrement - 1L) / objectBufferSizeIncrement
                    capacity =
                        minOf(
                            capacity.toLong() + increments * objectBufferSizeIncrement.toLong(),
                            effectiveLimit.toLong(),
                        ).toInt()
                    items.ensureCapacity(capacity)
                }
                items.add(item)
            }
            return RepeatableInMemoryCursorIteratorProvider(items.toList())
        }
    }
}

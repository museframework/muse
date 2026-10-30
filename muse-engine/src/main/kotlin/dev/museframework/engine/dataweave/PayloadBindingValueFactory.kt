package dev.museframework.engine.dataweave

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.streaming.MuseCursorIterator
import dev.museframework.engine.api.streaming.MuseCursorIteratorProvider
import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import java.io.IOException
import java.io.InputStream

internal object PayloadBindingValueFactory {
    fun payloadValue(event: MuseEvent, ownedBindings: MutableList<AutoCloseable>? = null): Any? =
        typedValue(event.payload, ownedBindings)

    fun typedValue(value: TypedValue<out Any>?, ownedBindings: MutableList<AutoCloseable>? = null): Any? {
        value ?: return null
        value.asCursorStreamProvider()?.let {
            return LazyCursorInputStream(it).also { binding -> ownedBindings?.add(binding) }
        }
        value.asCursorIteratorProvider<Any>()?.let {
            return LazyCursorIterator(it).also { binding -> ownedBindings?.add(binding) }
        }
        return value.getOrNull()
    }
}

private class LazyCursorInputStream(
    private val cursorProvider: MuseCursorStreamProvider,
) : InputStream() {
    private val lock = Any()
    private var cursor: MuseCursorStream? = null
    private var closed = false

    override fun read(): Int = activeCursor().read()

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = activeCursor().read(buffer, offset, length)

    override fun skip(amount: Long): Long = activeCursor().skip(amount)

    override fun available(): Int = activeCursor().available()

    override fun close() {
        val openedCursor =
            synchronized(lock) {
                if (closed) return
                closed = true
                cursor
            }
        openedCursor?.close()
    }

    private fun activeCursor(): MuseCursorStream =
        synchronized(lock) {
            if (closed) throw IOException("Streaming payload binding is closed")
            cursor ?: cursorProvider.openCursor().also { cursor = it }
        }
}

private class LazyCursorIterator<T>(
    private val cursorProvider: MuseCursorIteratorProvider<T>,
) : Iterator<T>, AutoCloseable {
    private val lock = Any()
    private var cursor: MuseCursorIterator<T>? = null
    private var closed = false

    override fun hasNext(): Boolean = activeCursor().hasNext()

    override fun next(): T = activeCursor().next()

    override fun close() {
        val openedCursor =
            synchronized(lock) {
                if (closed) return
                closed = true
                cursor
            }
        openedCursor?.close()
    }

    private fun activeCursor(): MuseCursorIterator<T> =
        synchronized(lock) {
            check(!closed) { "Streaming iterator binding is closed" }
            cursor ?: cursorProvider.openCursor().also { cursor = it }
        }
}

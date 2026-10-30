package dev.museframework.engine.api.streaming

interface MuseCursorIteratorProvider<T> : MuseCursorProvider<MuseCursorIterator<T>> {
    val size: Long
        get() = -1L
}

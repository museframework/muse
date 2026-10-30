package dev.museframework.engine.api.streaming

interface MuseCursorIterator<T> : Iterator<T>, MuseCursor {
    val size: Long
        get() = -1L
}

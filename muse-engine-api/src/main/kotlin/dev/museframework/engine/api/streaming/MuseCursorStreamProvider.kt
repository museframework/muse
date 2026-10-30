package dev.museframework.engine.api.streaming

interface MuseCursorStreamProvider : MuseCursorProvider<MuseCursorStream> {
    val size: Long
        get() = -1L
}

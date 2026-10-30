package dev.museframework.engine.api.streaming

import java.io.Closeable

interface MuseCursor : Closeable {
    val position: Long
    val isReleased: Boolean
    val provider: MuseCursorProvider<out MuseCursor>

    fun seek(position: Long)

    fun release()
}

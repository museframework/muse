package dev.museframework.engine.api.streaming

interface MuseCursorProvider<T : MuseCursor> : AutoCloseable {
    val isClosed: Boolean

    /** True while at least one engine registry owns this provider. */
    val isManaged: Boolean
        get() = StreamingProviderManagement.isManaged(this)

    fun openCursor(): T

    override fun close()

    fun releaseResources()
}

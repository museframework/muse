package dev.museframework.engine.api.streaming

/** An independent resource lifetime claim. Closing it is idempotent. */
class StreamingResourceLease internal constructor(
    private val registry: StreamingResourceRegistry,
    providers: List<MuseCursorProvider<out MuseCursor>> = emptyList(),
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var pins: List<MuseCursorProvider<out MuseCursor>>? = providers

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { registry.releaseAll() } finally { pins = null }
        }
    }
}

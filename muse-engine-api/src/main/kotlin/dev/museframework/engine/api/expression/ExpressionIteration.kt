package dev.museframework.engine.api.expression

import dev.museframework.engine.api.streaming.MuseCursorIterator
import dev.museframework.engine.api.streaming.NonRepeatableCursorIteratorProvider
import dev.museframework.engine.api.streaming.StreamingCleanupException
import dev.museframework.engine.api.streaming.StreamingResourceLease
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A locally owned evaluation result. Its consumer must close it on every exit.
 * Closing releases the result's cursor and private claim, not the event's input providers.
 */
class ExpressionIteration private constructor(
    private val cursor: MuseCursorIterator<Any?>,
    private val lease: StreamingResourceLease,
) : Iterator<Any?>, AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun hasNext(): Boolean = cursor.hasNext()
    override fun next(): Any? = cursor.next()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failures = mutableListOf<Throwable>()
        try { cursor.close() } catch (failure: Throwable) { failures += failure }
        try { lease.close() } catch (failure: Throwable) {
            if (failure is StreamingCleanupException) failures += failure.failures
            else failures += failure
        }
        if (failures.isNotEmpty()) throw StreamingCleanupException(failures)
    }

    companion object {
        /** Takes ownership of an iterator created for this evaluation, including its cleanup. */
        fun owned(iterator: Iterator<Any?>): ExpressionIteration {
            val provider = NonRepeatableCursorIteratorProvider(iterator)
            val lease = StreamingResourceRegistry.ownedClaim(provider)
            try {
                return ExpressionIteration(provider.openCursor(), lease)
            } catch (failure: Throwable) {
                try { lease.close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        /** Preserves legacy collection snapshots and borrowed scalar/iterator values. */
        fun snapshot(value: Any?): ExpressionIteration {
            val values = when (value) {
                is Collection<*> -> value.toList()
                is Array<*> -> value.toList()
                is Iterable<*> -> value.toList()
                else -> listOf(value)
            }
            return owned(values.iterator())
        }
    }
}

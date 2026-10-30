package dev.museframework.connectors.db.streaming

import dev.museframework.connectors.db.sql.readCurrentRow
import dev.museframework.engine.api.streaming.MuseCursorIterator
import dev.museframework.engine.api.streaming.MuseCursorIteratorProvider
import dev.museframework.engine.api.streaming.StreamMaximumSizeExceededException
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.api.streaming.StreamingProviderClosedException
import dev.museframework.engine.api.streaming.StreamingResourceReleasedException
import dev.museframework.engine.api.streaming.StreamingStrategy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicBoolean

internal class JdbcRowSource(
    private val connection: Connection,
    private val statement: PreparedStatement,
    private val resultSet: ResultSet,
    private val useColumnNumbers: Boolean,
) : AutoCloseable {
    private var prefetched = false
    private var hasRow = false
    private var closed = false

    fun hasNext(): Boolean {
        check(!closed) { "JDBC row source is closed" }
        if (!prefetched) {
            hasRow = resultSet.next()
            prefetched = true
        }
        return hasRow
    }

    fun next(): Map<String, Any?> {
        if (!hasNext()) throw NoSuchElementException()
        prefetched = false
        return resultSet.readCurrentRow(useColumnNumbers)
    }

    override fun close() {
        if (closed) return
        closed = true
        val failures = mutableListOf<Throwable>()
        closeResource(resultSet, failures)
        closeResource(statement, failures)
        closeResource(connection, failures)
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun closeResource(resource: AutoCloseable, failures: MutableList<Throwable>) {
        try {
            resource.close()
        } catch (failure: Throwable) {
            failures += failure
        }
    }
}

internal class JdbcRowCursorProvider(
    private var source: JdbcRowSource?,
    strategy: StreamingStrategy,
) : MuseCursorIteratorProvider<Map<String, Any?>> {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val repeatable: Boolean
    private val effectiveLimit: Int
    private val bufferIncrement: Int
    private var bufferCapacity: Int
    private val rows: ArrayList<Map<String, Any?>>
    private val cursors = mutableSetOf<JdbcCursor>()
    private var sourceExhausted = false
    private var nonRepeatableOpened = false

    init {
        val validated = validateStrategy(strategy)
        repeatable = validated.repeatable
        effectiveLimit = validated.effectiveLimit
        bufferIncrement = validated.bufferIncrement
        bufferCapacity = validated.bufferCapacity
        rows = ArrayList(bufferCapacity)
    }

    companion object {
        fun validateStrategy(strategy: StreamingStrategy): ValidatedStreamingStrategy =
            when (strategy) {
                is StreamingStrategy.RepeatableInMemory -> {
                    require(strategy.initialObjectBufferSize > 0) { "initialObjectBufferSize must be > 0" }
                    require(strategy.objectBufferSizeIncrement > 0) { "objectBufferSizeIncrement must be > 0" }
                    require(strategy.maxObjectBufferSize >= 0) { "maxObjectBufferSize must be >= 0" }
                    val effectiveLimit =
                        if (strategy.maxObjectBufferSize == 0) Int.MAX_VALUE else strategy.maxObjectBufferSize
                    ValidatedStreamingStrategy(
                        repeatable = true,
                        effectiveLimit = effectiveLimit,
                        bufferIncrement = strategy.objectBufferSizeIncrement,
                        bufferCapacity = minOf(strategy.initialObjectBufferSize, effectiveLimit),
                    )
                }

                StreamingStrategy.NonRepeatable ->
                    ValidatedStreamingStrategy(
                        repeatable = false,
                        effectiveLimit = 0,
                        bufferIncrement = 0,
                        bufferCapacity = 0,
                    )

                is StreamingStrategy.RepeatableFileStore ->
                    throw UnsupportedOperationException("Repeatable file-store object streams are not supported")
            }
    }

    internal data class ValidatedStreamingStrategy(
        val repeatable: Boolean,
        val effectiveLimit: Int,
        val bufferIncrement: Int,
        val bufferCapacity: Int,
    )

    override val isClosed: Boolean
        get() = closed.get()

    override val size: Long
        get() = synchronized(lock) { if (sourceExhausted && repeatable) rows.size.toLong() else -1L }

    override fun openCursor(): MuseCursorIterator<Map<String, Any?>> =
        synchronized(lock) {
            if (closed.get()) throw StreamingProviderClosedException()
            if (released.get()) throw StreamingResourceReleasedException()
            if (!repeatable && nonRepeatableOpened) {
                throw IllegalStateException("Non-repeatable database result has already been consumed")
            }
            if (!repeatable) nonRepeatableOpened = true
            JdbcCursor(this, repeatable).also(cursors::add)
        }

    override fun close() {
        synchronized(lock) {
            closed.set(true)
            if (cursors.isEmpty()) closeSource()
        }
    }

    override fun releaseResources() {
        val snapshot =
            synchronized(lock) {
                if (!released.compareAndSet(false, true)) return
                closed.set(true)
                closeSource()
                cursors.toList().also { cursors.clear() }
            }
        snapshot.forEach(JdbcCursor::release)
    }

    private fun hasNext(cursor: JdbcCursor): Boolean =
        synchronized(lock) {
            ensureReadable(cursor)
            if (repeatable) ensureBuffered(cursor.index) else sourceHasNext()
        }

    private fun next(cursor: JdbcCursor): Map<String, Any?> =
        synchronized(lock) {
            ensureReadable(cursor)
            val value =
                if (repeatable) {
                    if (!ensureBuffered(cursor.index)) throw NoSuchElementException()
                    rows[cursor.index]
                } else {
                    sourceNext()
                }
            cursor.index++
            value
        }

    private fun seek(
        cursor: JdbcCursor,
        position: Long,
    ) {
        synchronized(lock) {
            ensureReadable(cursor)
            require(position >= 0) { "position must be >= 0" }
            require(repeatable || position >= cursor.index) { "Non-repeatable iterator only supports forward seek" }
            require(position <= Int.MAX_VALUE) { "position exceeds iterator capacity" }
            val target = position.toInt()
            if (repeatable) {
                if (target > rows.size) {
                    while (rows.size < target && ensureBuffered(rows.size)) {
                        // ensureBuffered appends one row at a time.
                    }
                }
                require(target <= rows.size) { "position must be <= iterator size" }
                cursor.index = target
            } else {
                while (cursor.index < target && sourceHasNext()) {
                    sourceNext()
                    cursor.index++
                }
                require(cursor.index == target) { "position must be <= iterator size" }
            }
        }
    }

    private fun ensureBuffered(index: Int): Boolean {
        if (index < rows.size) return true
        if (sourceExhausted) return false
        if (!sourceHasNext()) {
            sourceExhausted = true
            return false
        }
        val requiredSize = rows.size.toLong() + 1L
        if (requiredSize > effectiveLimit.toLong()) {
            sourceExhausted = true
            closeSource()
            throw StreamMaximumSizeExceededException(effectiveLimit.toLong(), requiredSize)
        }
        if (rows.size == bufferCapacity && bufferCapacity < effectiveLimit) {
            bufferCapacity =
                minOf(bufferCapacity.toLong() + bufferIncrement, effectiveLimit.toLong()).toInt()
            rows.ensureCapacity(bufferCapacity)
        }
        rows += sourceNext()
        return true
    }

    private fun sourceHasNext(): Boolean {
        val current = source ?: return false
        return try {
            current.hasNext().also { hasNext ->
                if (!hasNext) closeSource()
            }
        } catch (failure: Throwable) {
            closeSource(failure)
            throw failure
        }
    }

    private fun sourceNext(): Map<String, Any?> {
        val current = source ?: throw NoSuchElementException()
        return try {
            current.next()
        } catch (failure: Throwable) {
            closeSource(failure)
            throw failure
        }
    }

    private fun ensureReadable(cursor: JdbcCursor) {
        if (released.get() || cursor.released.get()) throw StreamingResourceReleasedException()
        if (cursor.closed.get()) throw StreamingException("Cursor is closed")
    }

    private fun unregister(cursor: JdbcCursor) {
        synchronized(lock) {
            cursors.remove(cursor)
            if ((!repeatable || closed.get()) && cursors.isEmpty()) closeSource()
        }
    }

    private fun closeSource(cause: Throwable? = null) {
        val toClose = source ?: return
        source = null
        try {
            toClose.close()
        } catch (closeFailure: Throwable) {
            if (cause == null) throw closeFailure
            if (closeFailure !== cause) cause.addSuppressed(closeFailure)
        }
    }

    private class JdbcCursor(
        override val provider: JdbcRowCursorProvider,
        private val repeatable: Boolean,
    ) : MuseCursorIterator<Map<String, Any?>> {
        val closed = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        var index: Int = 0

        override val position: Long
            get() = index.toLong()

        override val isReleased: Boolean
            get() = released.get()

        override val size: Long
            get() = provider.size

        override fun hasNext(): Boolean = provider.hasNext(this)

        override fun next(): Map<String, Any?> = provider.next(this)

        override fun seek(position: Long) = provider.seek(this, position)

        override fun close() {
            if (closed.compareAndSet(false, true)) provider.unregister(this)
        }

        override fun release() {
            released.set(true)
            closed.set(true)
            provider.unregister(this)
        }
    }
}

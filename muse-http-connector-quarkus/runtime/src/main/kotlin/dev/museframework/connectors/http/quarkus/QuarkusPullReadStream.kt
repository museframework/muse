package dev.museframework.connectors.http.quarkus

import io.vertx.core.Handler
import io.vertx.core.buffer.Buffer
import io.vertx.core.streams.ReadStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pull-based [ReadStream] that feeds a Vert.x writer from a blocking [read]
 * function. Delivery is demand-bounded: elements are produced only while the
 * accumulated [fetch] demand is positive and the stream is not paused. The
 * [onClose] callback fires exactly once when the stream reaches EOF, fails,
 * or is abandoned by replacing the data handler with null.
 */
internal class QuarkusPullReadStream(
    private val read: (ByteArray) -> Int,
    private val onClose: () -> Unit = {},
) : ReadStream<Buffer> {
    private val buffer = ByteArray(DEFAULT_READ_BUFFER_SIZE)
    private val closeOnce = AtomicBoolean(false)
    private var demand = 0L
    private var paused = false
    private var ended = false
    private var handler: Handler<Buffer>? = null
    private var endHandler: Handler<Void>? = null
    private var exceptionHandler: Handler<Throwable>? = null

    override fun handler(handler: Handler<Buffer>?): ReadStream<Buffer> {
        if (handler == null) {
            this.handler = null
            this.endHandler = null
            this.exceptionHandler = null
            closeResource()
            return this
        }
        this.handler = handler
        drain()
        return this
    }

    override fun pause(): ReadStream<Buffer> {
        paused = true
        return this
    }

    override fun resume(): ReadStream<Buffer> {
        if (demand == 0L) demand = Long.MAX_VALUE
        paused = false
        drain()
        return this
    }

    override fun fetch(amount: Long): ReadStream<Buffer> {
        if (amount <= 0L) return this
        demand = if (demand == Long.MAX_VALUE) Long.MAX_VALUE else demand + amount
        paused = false
        drain()
        return this
    }

    override fun endHandler(endHandler: Handler<Void>?): ReadStream<Buffer> {
        this.endHandler = endHandler
        return this
    }

    override fun exceptionHandler(handler: Handler<Throwable>?): ReadStream<Buffer> {
        this.exceptionHandler = handler
        return this
    }

    private fun drain() {
        if (ended || paused || closeOnce.get()) return
        val handler = handler ?: return
        while (demand > 0L) {
            if (paused || closeOnce.get()) return
            try {
                val count = read(buffer)
                if (count == -1) {
                    ended = true
                    closeResource()
                    endHandler?.handle(null)
                    return
                }
                if (count > 0) {
                    demand--
                    handler.handle(Buffer.buffer(buffer.copyOfRange(0, count)))
                }
            } catch (failure: Throwable) {
                ended = true
                closeResource()
                exceptionHandler?.handle(failure)
                return
            }
        }
    }

    private fun closeResource() {
        if (closeOnce.compareAndSet(false, true)) {
            runCatching(onClose)
        }
    }

    companion object {
        internal const val DEFAULT_READ_BUFFER_SIZE = 8 * 1024
    }
}
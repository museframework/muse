package dev.museframework.connectors.http.quarkus

import io.vertx.core.buffer.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class QuarkusPullReadStreamTest {

    @Test
    fun `fetch delivers at most the accumulated demand and resumes only on further fetch`() {
        val payload = ByteArray(20_000) { index -> (index % 251).toByte() }
        val source = ByteArrayInputStream(payload)
        val closed = AtomicBoolean(false)
        val stream =
            QuarkusPullReadStream(
                read = { out -> source.read(out, 0, out.size) },
                onClose = { closed.set(true) },
            )
        val delivered = CopyOnWriteArrayList<Buffer>()
        val ended = CountDownLatch(1)
        stream.endHandler { ended.countDown() }
        stream.handler { delivered.add(it) }

        stream.fetch(2)

        assertEquals(2, delivered.size)
        assertFalse(closed.get())
        assertEquals(1, ended.count)

        stream.fetch(1)

        assertEquals(3, delivered.size)
        assertFalse(closed.get())
        assertEquals(1, ended.count)

        stream.fetch(1)

        assertTrue(ended.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(3, delivered.size)
        assertEquals(payload.size, delivered.sumOf { it.length() })
        assertTrue(closed.get())
    }

    @Test
    fun `flow starts only after demand is requested`() {
        val ended = CountDownLatch(1)
        val delivered = AtomicInteger(0)
        val stream = QuarkusPullReadStream(read = { -1 })

        stream.handler { delivered.incrementAndGet() }
        stream.endHandler { ended.countDown() }

        assertEquals(0, delivered.get())
        assertEquals(1, ended.count)

        stream.fetch(1)

        assertTrue(ended.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(0, delivered.get())
    }

    @Test
    fun `read failure closes the resource once and reports the exception`() {
        val closed = AtomicInteger(0)
        val failed = CountDownLatch(1)
        val stream =
            QuarkusPullReadStream(
                read = { throw IOException("boom") },
                onClose = { closed.incrementAndGet() },
            )

        stream.exceptionHandler { failed.countDown() }
        stream.handler { }

        stream.fetch(1)

        assertTrue(failed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, closed.get())
        stream.handler(null)
        assertEquals(1, closed.get())
    }

    @Test
    fun `replacing the handler with null closes the resource`() {
        val closed = AtomicBoolean(false)
        val stream = QuarkusPullReadStream(read = { -1 }, onClose = { closed.set(true) })

        stream.handler { }
        stream.handler(null)

        assertTrue(closed.get())
    }

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 5L
    }
}
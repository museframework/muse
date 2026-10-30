package dev.museframework.engine.dataweave

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.EvaluationRequest
import dev.museframework.engine.api.expression.IterationSource
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.streaming.NonRepeatableCursorStreamProvider
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.*

class CsvStreamingBindingTest {
    @Test
    fun `non CSV MIME metadata does not become unsupported reader options`() {
        val executor = MuseDataWeaveExecutor()
        val text = MuseEvent(payload = TypedValue.of(
            ByteArrayInputStream("hello".toByteArray()), "text/plain; format=flowed",
        ))
        val json = MuseEvent(payload = TypedValue.of(
            ByteArrayInputStream("{\"x\":1}".toByteArray()), "application/json; profile=example",
        ))
        assertEquals("hello", executor.evaluate("payload", DataWeaveContext(text))?.value)
        assertEquals(1, (executor.evaluate("payload", DataWeaveContext(json))?.value as Map<*, *>)["x"])
    }

    @Test
    fun `CSV metadata does not opt unrelated mutable collections into lazy iteration`() {
        val input = CountingInput("id,name\n1,one\n".toByteArray())
        val items = mutableListOf("one", "two")
        val event = MuseEvent(
            payload = TypedValue.of(input, "application/csv; streaming=true; header=true"),
            variables = mapOf("items" to TypedValue.of(items)),
        )
        assertNull(MuseDataWeaveExecutor().tryOpenIteration("vars.items", DataWeaveContext(event)))
        assertEquals(0, input.readBytes)
        assertFalse(input.closed)
    }

    @Test
    fun `closing a CSV iterator preserves repeatability and retained provider ownership`() {
        val bytes = "id,name\n1,one\n2,two\n".toByteArray()
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes(bytes)
        val registry = StreamingResourceRegistry()
        registry.register(provider)
        val lease = registry.retain(listOf(provider))
        try {
            val event = MuseEvent(payload = TypedValue.of(provider, "application/csv; streaming=true; header=true"))
            val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
            val iterator = evaluator.evaluate(EvaluationRequest.Iteration(IterationSource.Expression("#[payload]")), event)
            assertEquals("1", (iterator.next() as Map<*, *>)["id"])
            iterator.close()
            assertFalse(provider.isClosed)
            registry.releaseAll()
            assertFalse(provider.isClosed)
            provider.openCursor().use { assertContentEquals(bytes, it.readBytes()) }
            val second = evaluator.evaluate(EvaluationRequest.Iteration(IterationSource.Expression("#[payload]")), event)
            second.use { assertEquals("1", (it.next() as Map<*, *>)["id"]) }
        } finally {
            registry.releaseAll()
            lease.close()
        }
        assertTrue(provider.isClosed)
    }

    @Test
    fun `closing a non repeatable CSV iterator leaves source cleanup to the registry`() {
        val input = CountingInput("id,name\n1,one\n2,two\n".toByteArray())
        val provider = NonRepeatableCursorStreamProvider(input)
        val registry = StreamingResourceRegistry()
        registry.register(provider)
        try {
            val event = MuseEvent(payload = TypedValue.of(provider, "application/csv; streaming=true; header=true"))
            val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
            evaluator.evaluate(EvaluationRequest.Iteration(IterationSource.Expression("#[payload]")), event).use {
                assertEquals("1", (it.next() as Map<*, *>)["id"])
            }
            assertFalse(provider.isClosed)
            assertFalse(input.closed)
            assertFailsWith<IllegalStateException> { provider.openCursor() }
        } finally {
            registry.releaseAll()
        }
        assertTrue(provider.isClosed)
        assertTrue(input.closed)
    }

    @Test
    fun `backend declines unsupported MIME types without consuming input`() {
        for (mime in listOf("application/csv", "application/csv; streaming=false", "application/json; streaming=true", "application/json; charset=unknown-encoding")) {
            val input = CountingInput("id,name\n1,one\n".toByteArray())
            val event = MuseEvent(payload = TypedValue.of(input, mime))
            assertNull(MuseDataWeaveExecutor().tryOpenIteration("payload", DataWeaveContext(event)), mime)
            assertEquals(0, input.readBytes, mime)
            assertFalse(input.closed, mime)
        }
    }

    @Test
    fun `backend declines collection scripts without reading the CSV input`() {
        val source = CountingInput("id,name\n1,one\n".toByteArray())
        val event = MuseEvent(payload = TypedValue.of(source, "application/csv; streaming=true; header=true"))
        assertNull(MuseDataWeaveExecutor().tryOpenIteration("output application/java --- payload", DataWeaveContext(event)))
        assertEquals(0, source.readBytes)
        assertFalse(source.closed)
    }

    @Test
    fun `streaming collection evaluation reads one CSV row without consuming the complete file`() {
        val bytes = buildString {
            appendLine("id,name")
            repeat(10000) { appendLine("$it,name-$it") }
        }.toByteArray()
        val source = CountingInput(bytes)
        val event = MuseEvent(payload = TypedValue.of(source, "application/csv; streaming=true; header=true"))
        val evaluator: MuseExpressionEvaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        val iterator = evaluator.evaluate(EvaluationRequest.Iteration(IterationSource.Expression("#[payload]")), event)

        assertTrue(source.readBytes < bytes.size)
        assertEquals("0", (iterator.next() as Map<*, *>)["id"])
        assertTrue(source.readBytes < bytes.size)
        iterator.close()
        assertTrue(source.closed)
    }

    @Test
    fun `public iteration fallback materializes unsupported CSV expressions before consumption`() {
        val bytes = buildString {
            appendLine("id,name")
            repeat(10000) { appendLine("$it,name-$it") }
        }.toByteArray()
        val source = CountingInput(bytes)
        val event = MuseEvent(payload = TypedValue.of(source, "application/csv; streaming=true; header=true"))
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        evaluator.openIteration(
            EvaluationRequest.Iteration(IterationSource.Expression("#[payload map ((row) -> row)]")), event,
        ).use {
            assertEquals(bytes.size, source.readBytes)
            assertEquals("0", (it.next() as Map<*, *>)["id"])
            assertEquals(9999, it.asSequence().count())
        }
    }

    @Test
    fun `public variable fallback preserves snapshots without reading unrelated CSV input`() {
        val source = CountingInput("id,name\n1,one\n".toByteArray())
        val items = mutableListOf("one", "two")
        val event = MuseEvent(
            payload = TypedValue.of(source, "application/csv; streaming=true; header=true"),
            variables = mapOf("items" to TypedValue.of(items)),
        )
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), Properties())
        evaluator.openIteration(EvaluationRequest.Iteration(IterationSource.Expression("#[vars.items]")), event).use {
            items[1] = "changed"
            assertEquals(listOf("one", "two"), it.asSequence().toList())
            assertEquals(0, source.readBytes)
        }
        assertFalse(source.closed)
    }

    private class CountingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var readBytes = 0
        var closed = false
        override fun read(): Int = super.read().also { if (it >= 0) readBytes++ }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, length).also { if (it > 0) readBytes += it }
        override fun close() { closed = true; super.close() }
    }

    @Test
    fun `CSV MIME parameters reach the reader and charset is not a reader option`() {
        val event = MuseEvent(payload = TypedValue.of(
            ByteArrayInputStream("a|b\nc|d\n".toByteArray()),
            "application/csv; header=false; separator=\"|\"; charset=UTF-8",
        ))
        val result = MuseDataWeaveExecutor().evaluate("payload", DataWeaveContext(event))
        assertEquals(listOf(
            mapOf("column_0" to "a", "column_1" to "b"),
            mapOf("column_0" to "c", "column_1" to "d"),
        ), result?.value)
    }

    @Test
    fun `CSV payload charset controls byte decoding`() {
        val event = MuseEvent(payload = TypedValue.of(
            ByteArrayInputStream("name\ncaffè\n".toByteArray(StandardCharsets.ISO_8859_1)),
            "application/csv; header=true; charset=ISO-8859-1",
        ))
        assertEquals("caffè", MuseDataWeaveExecutor().evaluate("payload[0].name", DataWeaveContext(event))?.value)
    }
}

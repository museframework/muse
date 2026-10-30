package dev.museframework.engine.api.expression

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.streaming.StreamingCleanupException
import org.junit.jupiter.api.Test
import kotlin.test.*

class EvaluationRequestTest {
    @Test
    fun `value requests preserve legacy evaluation and metadata`() {
        val value = TypedValue.of<Any>("value", "text/plain; charset=UTF-8")
        val evaluator = LegacyEvaluator(value)
        assertSame(value, evaluator.evaluate(EvaluationRequest.Value("#[vars.answer]"), MuseEvent()))
        assertEquals(listOf("#[vars.answer]"), evaluator.calls)
    }

    @Test
    fun `raw event payload iteration does not evaluate an expression`() {
        val evaluator = LegacyEvaluator(TypedValue.of<Any>(listOf("evaluated")))
        val event = MuseEvent(payload = TypedValue.of(listOf("raw")))
        evaluator.evaluate(EvaluationRequest.Iteration(IterationSource.EventPayload), event).use {
            assertEquals(listOf("raw"), it.asSequence().toList())
        }
        assertTrue(evaluator.calls.isEmpty())
        evaluator.evaluate(EvaluationRequest.Iteration(IterationSource.Expression("#[payload]")), event).use {
            assertEquals(listOf("evaluated"), it.asSequence().toList())
        }
        assertEquals(listOf("#[payload]"), evaluator.calls)
    }

    @Test
    fun `legacy iteration snapshots mutable collections before consumption`() {
        val items = mutableListOf("one", "two")
        val evaluator = LegacyEvaluator(TypedValue.of<Any>(items))
        evaluator.evaluate(EvaluationRequest.Iteration(IterationSource.Expression("#[vars.items]")), MuseEvent()).use {
            assertEquals("one", it.next())
            items[1] = "changed"
            assertEquals("two", it.next())
        }
    }

    @Test
    fun `legacy iteration keeps null scalars and borrowed iterators as singleton values`() {
        val borrowed = TrackingIterator()
        for (value in listOf(null, 42, borrowed)) {
            val evaluator = LegacyEvaluator(TypedValue.of<Any>(value))
            evaluator.evaluate(EvaluationRequest.Iteration(IterationSource.Expression("#[vars.items]")), MuseEvent()).use {
                assertEquals(listOf(value), it.asSequence().toList())
            }
        }
        assertEquals(0, borrowed.closes)
        assertEquals(0, borrowed.reads)
    }

    @Test
    fun `owned iteration releases its reader once even on early exit`() {
        val reader = TrackingIterator()
        val result = ExpressionIteration.owned(reader)
        assertEquals(1, result.next())
        result.close()
        result.close()
        assertEquals(1, reader.reads)
        assertEquals(1, reader.closes)
    }

    @Test
    fun `owned iteration reports cleanup failure without retrying delegate cleanup`() {
        val failure = IllegalStateException("reader cleanup")
        val reader = TrackingIterator(failure)
        val result = ExpressionIteration.owned(reader)
        val error = assertFailsWith<StreamingCleanupException> { result.close() }
        assertTrue(error.failures.any { it === failure || (it as? StreamingCleanupException)?.failures?.contains(failure) == true })
        result.close()
        assertEquals(1, reader.closes)
    }

    @Test
    fun `named iteration entry preserves custom iteration dispatch and ownership`() {
        val reader = TrackingIterator()
        val result = ExpressionIteration.owned(reader)
        val evaluator = object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? =
                throw AssertionError("Value evaluation was not requested")
            override fun evaluate(request: EvaluationRequest.Iteration, event: MuseEvent) = result
        }
        evaluator.openIteration(EvaluationRequest.Iteration(IterationSource.EventPayload), MuseEvent()).use {
            assertSame(result, it)
            assertEquals(1, it.next())
        }
        assertEquals(1, reader.closes)
    }

    @Test
    fun `value intent preserves a streaming provider without opening or materializing it`() {
        val input = java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val provider = dev.museframework.engine.api.streaming.NonRepeatableCursorStreamProvider(input)
        val value = TypedValue.of<Any>(provider, "application/octet-stream")
        try {
            assertSame(value, LegacyEvaluator(value).evaluate(EvaluationRequest.Value("#[payload]"), MuseEvent()))
            assertEquals(3, input.available())
            provider.openCursor().use { assertEquals(1, it.read()) }
        } finally {
            provider.releaseResources()
        }
    }

    @Test
    fun `legacy fallback eagerly snapshots a custom Iterable during opening`() {
        var reads = 0
        val items = Iterable {
            object : Iterator<Int> {
                override fun hasNext() = reads < 3
                override fun next() = ++reads
            }
        }
        val evaluator = LegacyEvaluator(TypedValue.of<Any>(items))
        evaluator.openIteration(EvaluationRequest.Iteration(IterationSource.EventPayload), MuseEvent(payload = TypedValue.of(items))).use {
            assertEquals(3, reads)
            assertEquals(listOf(1, 2, 3), it.asSequence().toList())
        }
    }

    private class LegacyEvaluator(val value: TypedValue<Any>?) : MuseExpressionEvaluator {
        val calls = mutableListOf<String>()
        override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? {
            calls += expression
            return value
        }
    }

    private class TrackingIterator(val failure: Throwable? = null) : Iterator<Int>, AutoCloseable {
        var reads = 0
        var closes = 0
        override fun hasNext() = true
        override fun next() = ++reads
        override fun close() { closes++; failure?.let { throw it } }
    }
}

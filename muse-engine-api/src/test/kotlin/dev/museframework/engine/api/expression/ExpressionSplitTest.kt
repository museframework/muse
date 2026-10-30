package dev.museframework.engine.api.expression

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.*

class ExpressionSplitTest {
    @Test
    fun `split preserves custom iteration dispatch metadata and local cleanup`() {
        val item = TypedValue.of<Any>("record", "text/plain", StandardCharsets.ISO_8859_1)
        var closed = false
        val evaluator = object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? =
                throw AssertionError("Value evaluation was not requested")

            override fun openIteration(request: EvaluationRequest.Iteration, event: MuseEvent): ExpressionIteration {
                val iterator = object : Iterator<Any?>, AutoCloseable {
                    var available = true
                    override fun hasNext() = available
                    override fun next(): Any { available = false; return item }
                    override fun close() { closed = true }
                }
                return ExpressionIteration.owned(iterator)
            }
        }
        evaluator.split("#[payload]", MuseEvent()).use {
            assertSame(item, it.next())
            assertFalse(it.hasNext())
        }
        assertTrue(closed)
    }
}

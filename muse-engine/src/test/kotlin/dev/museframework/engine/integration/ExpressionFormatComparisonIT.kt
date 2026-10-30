package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class ExpressionFormatComparisonIT {

    private val dataWeaveExecutor = MuseDataWeaveExecutor()
    private val properties = Properties()

    @Test
    fun `test expression with wrapper`() {
        val evaluator = MuseExpressionEvaluatorImpl(dataWeaveExecutor, properties)
        
        val event = MuseEvent(
            payload = TypedValue.of("fail")
        )
        
        // Test with #[...] wrapper (as in Mule XML)
        val result = evaluator.evaluate("#[payload == \"fail\"]", event)
        println("With wrapper #[...]: ${result?.value}")
        assertEquals(true, result?.value)
    }

    @Test
    fun `test expression without wrapper`() {
        val evaluator = MuseExpressionEvaluatorImpl(dataWeaveExecutor, properties)
        
        val event = MuseEvent(
            payload = TypedValue.of("fail")
        )
        
        // Test with wrapper #[...] - expressions must be wrapped
        val result = evaluator.evaluate("""#[payload == "fail"]""", event)
        println("Without wrapper: ${result?.value}")
        assertEquals(true, result?.value)
    }
}

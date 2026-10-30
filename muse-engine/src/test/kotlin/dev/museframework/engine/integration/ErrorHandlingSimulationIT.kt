package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class ErrorHandlingSimulationIT {

    private val dataWeaveExecutor = MuseDataWeaveExecutor()
    private val properties = Properties()

    @Test
    fun `simulate ErrorHandlingE2ETest scenario`() {
        val evaluator = MuseExpressionEvaluatorImpl(dataWeaveExecutor, properties)
        
        // Simulate exactly what HttpListenerMuseSource does with text/plain body "fail"
        val event = MuseEvent(
            payload = TypedValue.of("fail", "text/plain")
        )
        
        println("Payload value: '${event.payload?.value}'")
        println("Payload type: ${event.payload?.value?.javaClass}")
        
        // Test the exact expression from error-handling.xml
        val expression = "#[payload == \"fail\"]"
        println("Expression: $expression")
        
        val result = evaluator.evaluate(expression, event)
        println("Result: ${result?.value} (type: ${result?.value?.javaClass})")
        
        assertEquals(true, result?.value, "Expression should evaluate to true")
    }

    @Test
    fun `test with raw string comparison`() {
        val evaluator = MuseExpressionEvaluatorImpl(dataWeaveExecutor, properties)
        
        val event = MuseEvent(
            payload = TypedValue.of("fail")
        )
        
        // Test without wrapper
        val result1 = evaluator.evaluate("payload == \"fail\"", event)
        println("Without wrapper: ${result1?.value}")
        
        // Test with wrapper
        val result2 = evaluator.evaluate("#[payload == \"fail\"]", event)
        println("With wrapper: ${result2?.value}")
        
        assertEquals(true, result2?.value)
    }
}

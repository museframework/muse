package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Test to verify that expressions with #[...] wrapper work correctly
 */
class ExpressionFormatIT {

    private val dataWeaveExecutor = MuseDataWeaveExecutor()
    private val properties = Properties()

    private fun createEvaluator(): MuseExpressionEvaluatorImpl {
        return MuseExpressionEvaluatorImpl(dataWeaveExecutor, properties)
    }

    private fun createContext(): DataWeaveContext {
        return DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes = TypedValue.of(mapOf(
                    "queryParams" to mapOf("type" to "A"),
                    "headers" to mapOf("auth" to "token")
                ))
            ),
            Properties()
        )
    }

    @Test
    fun `direct expression without wrapper`() {
        val context = createContext()
        
        // Direct expression (no #[...])
        val result = dataWeaveExecutor.evaluate("""attributes.queryParams."type" """, context)
        
        println("Direct: attributes.queryParams.\"type\" = ${result?.value}")
        assertEquals("A", result?.value)
    }

    @Test
    fun `expression with wrapper via evaluator`() {
        val evaluator = createEvaluator()
        
        val event = MuseEvent(
            payload = TypedValue.of(null),
            attributes = TypedValue.of(mapOf(
                "queryParams" to mapOf("type" to "A")
            ))
        )
        
        // Expression with #[...] wrapper (as in Mule XML)
        val result = evaluator.evaluate("""#[attributes.queryParams."type"]""", event)
        
        println("Wrapped: #[attributes.queryParams.\"type\"] = ${result?.value}")
        assertEquals("A", result?.value)
    }

    @Test
    fun `comparison with wrapper`() {
        val evaluator = createEvaluator()
        
        val event = MuseEvent(
            payload = TypedValue.of(null),
            attributes = TypedValue.of(mapOf(
                "queryParams" to mapOf("type" to "A")
            ))
        )
        
        // Comparison with #[...] wrapper
        val result = evaluator.evaluate("""#[attributes.queryParams."type" == 'A']""", event)
        
        println("Comparison: #[attributes.queryParams.\"type\" == 'A'] = ${result?.value}")
        assertEquals(true, result?.value)
    }
}

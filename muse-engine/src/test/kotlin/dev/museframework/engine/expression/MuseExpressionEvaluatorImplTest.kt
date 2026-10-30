package dev.museframework.engine.expression

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.DataWeaveExecutor
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.*

class MuseExpressionEvaluatorImplTest {

    private val dataWeaveExecutor = mockk<DataWeaveExecutor>()
    private val evaluator = MuseExpressionEvaluatorImpl(dataWeaveExecutor, Properties())
    private val event = MuseEvent(payload = TypedValue.of("test-payload"))
    private val context = DataWeaveContext(event)

    @Test
    fun `should return literal string when no expression present`() {
        val result = evaluator.evaluate("hello world", event)

        assertNotNull(result)
        assertEquals("hello world", result?.value)
        assertEquals(TypedValue.MIME_TEXT, result?.mimeType)
        verify { dataWeaveExecutor wasNot Called }
    }

    @Test
    fun `should evaluate pure expression and preserve type`() {
        val expected: TypedValue<Any> = TypedValue.of(mapOf("name" to "John"), TypedValue.MIME_JSON)
        every { dataWeaveExecutor.evaluate("payload.name", context) } returns expected

        val result = evaluator.evaluate("#[payload.name]", event)

        assertEquals(expected, result)
        verify(exactly = 1) { dataWeaveExecutor.evaluate("payload.name", context) }
    }

    @Test
    fun `should evaluate interpolated string and return text`() {
        every { dataWeaveExecutor.evaluate("payload.name", context) } returns
                TypedValue.of("John", TypedValue.MIME_TEXT)
        every { dataWeaveExecutor.evaluate("payload.surname", context) } returns
                TypedValue.of("Doe", TypedValue.MIME_TEXT)

        val result = evaluator.evaluate("Hello #[payload.name] #[payload.surname]!", event)

        assertNotNull(result)
        assertEquals("Hello John Doe!", result?.value)
        assertEquals(TypedValue.MIME_TEXT, result?.mimeType)
    }

    @Test
    fun `should handle null result from dataweave in interpolation`() {
        every { dataWeaveExecutor.evaluate("payload.missing", context) } returns null

        val result = evaluator.evaluate("Value: #[payload.missing]", event)

        assertNotNull(result)
        assertEquals("Value: ", result?.value)
        assertEquals(TypedValue.MIME_TEXT, result?.mimeType)
    }

    @Test
    fun `should evaluate multiline pure expression`() {
        val dwExpression = "%dw 2.0\noutput application/json\n---\npayload"
        val expected: TypedValue<Any>? = TypedValue.of(mapOf("key" to "value"), TypedValue.MIME_JSON)
        every { dataWeaveExecutor.evaluate(dwExpression, context) } returns expected

        val result = evaluator.evaluate("#[$dwExpression]", event)

        assertEquals(expected, result)
    }

    @Test
    fun `should handle adjacent expressions in interpolation`() {
        every { dataWeaveExecutor.evaluate("payload.name", context) } returns
                TypedValue.of("John", TypedValue.MIME_TEXT)
        every { dataWeaveExecutor.evaluate("payload.surname", context) } returns
                TypedValue.of("Doe", TypedValue.MIME_TEXT)

        val result = evaluator.evaluate("#[payload.name]#[payload.surname]", event)

        assertNotNull(result)
        assertEquals("JohnDoe", result?.value)
        assertEquals(TypedValue.MIME_TEXT, result?.mimeType)
    }
}

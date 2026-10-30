package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.DataWeaveException
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Properties

/**
 * Captures the verified navigation behavior for nested Java maps.
 *
 * Bracket notation works for every tested key. Dot notation also works for a
 * non-keyword nested key, but a key named `type`, which is a DataWeave
 * keyword, must use brackets.
 */
class DataWeaveNavigationInvestigationIT {

    private val executor = MuseDataWeaveExecutor()

    private fun createContext(): DataWeaveContext =
        DataWeaveContext.from(
            MuseEvent(
                payload = TypedValue.of(null),
                attributes =
                    TypedValue.of(
                        mapOf(
                            "queryParams" to mapOf("type" to "A", "id" to "123"),
                            "headers" to mapOf("Authorization" to "Bearer token123"),
                        ),
                    ),
            ),
            Properties(),
        )

    @Test
    fun `01 - single level access works`() {
        val context = createContext()

        val result = executor.evaluate("attributes.queryParams", context)

        assertEquals(mapOf("type" to "A", "id" to "123"), result?.value)
    }

    @Test
    fun `02 - bracket notation on nested map works`() {
        val context = createContext()

        val result = executor.evaluate("attributes.queryParams['type']", context)

        assertEquals("A", result?.value)
    }

    @Test
    fun `03 - DataWeave keyword used as nested key requires brackets`() {
        val context = createContext()

        val exception =
            assertThrows<DataWeaveException> {
                executor.evaluate("attributes.queryParams.type", context)
            }

        assertEquals("Failed to evaluate expression: attributes.queryParams.type", exception.message)
        assertNotNull(exception.cause)
    }

    @Test
    fun `04 - comparison with bracket notation works`() {
        val context = createContext()

        val result = executor.evaluate("attributes.queryParams['type'] == 'A'", context)

        assertEquals(true, result?.value)
    }

    @Test
    fun `05 - comparison with DataWeave keyword key requires brackets`() {
        val context = createContext()

        val exception =
            assertThrows<DataWeaveException> {
                executor.evaluate("attributes.queryParams.type == 'A'", context)
            }

        assertEquals(
            "Failed to evaluate expression: attributes.queryParams.type == 'A'",
            exception.message,
        )
        assertNotNull(exception.cause)
    }

    @Test
    fun `06 - accessing headers with bracket notation works`() {
        val context = createContext()

        val result = executor.evaluate("attributes.headers['Authorization']", context)

        assertEquals("Bearer token123", result?.value)
    }

    @Test
    fun `07 - accessing non-keyword nested key with dot notation works`() {
        val context = createContext()

        val result = executor.evaluate("attributes.headers.Authorization", context)

        assertEquals("Bearer token123", result?.value)
    }

    @Test
    fun `08 - single level map access with dot notation works`() {
        val context =
            DataWeaveContext.from(
                MuseEvent(
                    payload = TypedValue.of(null),
                    attributes =
                        TypedValue.of(
                            mapOf(
                                "statusCode" to 200,
                                "path" to "/api/test",
                            ),
                        ),
                ),
                Properties(),
            )

        val result = executor.evaluate("attributes.statusCode", context)

        assertEquals(200, result?.value)
    }
}

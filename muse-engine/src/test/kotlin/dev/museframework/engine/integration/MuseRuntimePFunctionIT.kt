package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.DataWeaveException
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.assertThrows
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MuseRuntimePFunctionIT {

    private fun executor() = MuseDataWeaveExecutor()

    private fun context(
        properties: Properties = Properties(),
        payload: Any = "test",
    ) = DataWeaveContext(
        event = MuseEvent(payload = TypedValue.of(payload)),
        properties = properties,
    )

    private fun properties(vararg pairs: Pair<String, String>) =
        Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    // --- happy path ---

    @Test
    fun `should return property value`() {
        val props = properties("db.host" to "localhost")
        val result = executor().evaluate("p('db.host')", context(props))
        assertEquals("localhost", result?.getOrNull())
    }

    @Test
    fun `should return property value already resolved at bootstrap`() {
        // placeholder resolution happens at bootstrap via PropertiesLoader
        // at runtime p() returns the already-resolved value
        val props = properties(
            "base.url" to "http://localhost:8080",
            "api.url" to "http://localhost:8080/api",
        )
        val result = executor().evaluate("p('api.url')", context(props))
        assertEquals("http://localhost:8080/api", result?.getOrNull())
    }

    @Test
    fun `should return property with special characters in value`() {
        val props = properties("jdbc.url" to "jdbc:postgresql://localhost:5432/mydb?ssl=true")
        val result = executor().evaluate("p('jdbc.url')", context(props))
        assertEquals("jdbc:postgresql://localhost:5432/mydb?ssl=true", result?.getOrNull())
    }

    // --- error cases ---

    @Test
    fun `should throw when property not found`() {
        val exception = assertThrows<DataWeaveException> {
            executor().evaluate("p('missing.key')", context())
        }

        assertEquals("Failed to evaluate expression: p('missing.key')", exception.message)
        assertNotNull(exception.cause)
    }

    @Test
    fun `should throw with clear message when properties are empty`() {
        val exception = assertThrows<DataWeaveException> {
            executor().evaluate("p('any.key')", context())
        }

        assertEquals("Failed to evaluate expression: p('any.key')", exception.message)
        assertNotNull(exception.cause)
    }

    // --- integration with other bindings ---

    @Test
    fun `should use p() in string interpolation`() {
        val props = properties("db.host" to "localhost")
        val result = executor().evaluate(
            "\"host=\" ++ p('db.host')",
            context(props),
        )
        assertEquals("host=localhost", result?.getOrNull())
    }

    @Test
    fun `should use p() combined with payload`() {
        val props = properties("db.host" to "localhost")
        val result = executor().evaluate(
            """
            %dw 2.0
            output application/java
            ---
            {
                host: p('db.host'),
                data: payload
            }
            """.trimIndent(),
            context(props, payload = "my-payload"),
        )
        assertNotNull(result?.getOrNull())
        val map = result?.getOrNull() as Map<*, *>
        assertEquals("localhost", map["host"])
        assertEquals("my-payload", map["data"])
    }

    @Test
    fun `should use p() inside transform expression`() {
        val props = properties(
            "app.name" to "muse",
            "app.version" to "1.0.0",
        )
        val result = executor().evaluate(
            """
            %dw 2.0
            output application/java
            ---
            p('app.name') ++ "-" ++ p('app.version')
            """.trimIndent(),
            context(props),
        )
        assertEquals("muse-1.0.0", result?.getOrNull())
    }

    @Test
    fun `should use p() with multiple properties in object`() {
        val props = properties(
            "db.host" to "localhost",
            "db.port" to "5432",
            "db.name" to "mydb",
        )
        val result = executor().evaluate(
            """
            %dw 2.0
            output application/java
            ---
            {
                host: p('db.host'),
                port: p('db.port'),
                name: p('db.name')
            }
            """.trimIndent(),
            context(props),
        )
        val map = result?.getOrNull() as Map<*, *>
        assertEquals("localhost", map["host"])
        assertEquals("5432", map["port"])
        assertEquals("mydb", map["name"])
    }

    // --- explicit import ---

    @Test
    fun `should work with explicit import from dw Muse Runtime`() {
        val props = properties("test.key" to "explicit-import-value")
        val result = executor().evaluate(
            """
            %dw 2.0
            import p from dw::Muse::Runtime
            output application/java
            ---
            p('test.key')
            """.trimIndent(),
            context(props),
        )
        assertEquals("explicit-import-value", result?.getOrNull())
    }

    @Test
    fun `should work with wildcard import from dw Muse Runtime`() {
        val props = properties("test.key" to "wildcard-import-value")
        val result = executor().evaluate(
            """
            %dw 2.0
            import * from dw::Muse::Runtime
            output application/java
            ---
            p('test.key')
            """.trimIndent(),
            context(props),
        )
        assertEquals("wildcard-import-value", result?.getOrNull())
    }
}

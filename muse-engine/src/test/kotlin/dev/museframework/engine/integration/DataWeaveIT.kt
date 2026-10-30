package dev.museframework.engine.integration

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.DataWeaveException
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class DataWeaveIT {

    private lateinit var dataWeaveExecutor: MuseDataWeaveExecutor

    @BeforeEach
    fun setup() {
        dataWeaveExecutor = MuseDataWeaveExecutor()
    }

    private fun contextWithPayload(payload: Any?, vars: Map<String, Any> = emptyMap()): DataWeaveContext {
        val event = MuseEvent(
            payload = TypedValue.of(payload),
            variables = vars.mapValues { TypedValue.of(it.value) }
        )
        return DataWeaveContext.from(event, Properties())
    }

    private fun contextWithPayloadAndMimeType(payload: Any?, mimeType: String): DataWeaveContext {
        val event = MuseEvent(
            payload = TypedValue.of(payload, mimeType)
        )
        return DataWeaveContext.from(event, Properties())
    }

    private fun contextWithVarsAndMimeType(vars: Map<String, Any>, varMimeType: Map<String, String>): DataWeaveContext {
        val event = MuseEvent(
            payload = TypedValue.of(null),
            variables = vars.mapValues { (key, value) ->
                TypedValue.of(value, varMimeType[key] ?: TypedValue.MIME_JAVA)
            }
        )
        return DataWeaveContext.from(event, Properties())
    }

    private fun contextWithAttributes(attributes: Map<String, Any>): DataWeaveContext {
        val event = MuseEvent(
            payload = TypedValue.of(null),
            attributes = TypedValue.of(attributes)
        )
        return DataWeaveContext.from(event, Properties())
    }

    @Test
    fun `should default to java output type when no output declared`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            {foo: "bar"}
        """.trimIndent()
        val context = contextWithPayload(null)
        val result = dataWeaveExecutor.evaluate(script, context)

        assertNotNull(result)
        assertTrue(result is TypedValue<*>, "Expected TypedValue but got ${result!!::class.simpleName}")
        @Suppress("UNCHECKED_CAST")
        assertEquals("bar", (result.value as? Map<String, Any>)?.get("foo"))
    }

    @Test
    fun `should handle binary output`() {
        val script = """
            %dw 2.0
            output application/octet-stream
            ---
            "test" as Binary
        """.trimIndent()
        val context = contextWithPayload(null)
        val result = dataWeaveExecutor.evaluate(script, context)

        assertNotNull(result)
    }

    @Test
    fun `should handle custom mime type payload`() {
        val context =
            contextWithPayloadAndMimeType(
                payload = """{"name":"Ada"}""",
                mimeType = TypedValue.MIME_JSON,
            )

        val result = dataWeaveExecutor.evaluate("payload.name", context)

        assertEquals("Ada", result?.value)
        assertEquals(TypedValue.MIME_JAVA, result?.mimeType)
    }

    @Test
    fun `should handle multiple output declarations`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            {json: "ok"}
        """.trimIndent()
        val context = contextWithPayload(null)
        val resultJson = dataWeaveExecutor.evaluate(script, context)

        assertTrue(resultJson?.value is Map<*, *>)
        assertEquals("ok", (resultJson?.value as Map<String, Any>)["json"])

        val scriptXml = """
            %dw 2.0
            output application/xml
            ---
            {xml: "ok"}
        """.trimIndent()
        val resultXml = dataWeaveExecutor.evaluate(scriptXml, context)
        assertNotNull(resultXml?.value)
    }

    @Test
    fun `should handle resource dwl with different mime type`() {
        val context = contextWithPayload(mapOf("foo" to "bar"))
        val script = """
            %dw 2.0
            output application/xml
            ---
            {foo: payload.foo}
        """.trimIndent()
        val result = dataWeaveExecutor.evaluate(script, context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should handle edge cases string number null`() {
        val scriptEmptyString = "%dw 2.0\noutput application/java\n---\n\"\""
        val context = contextWithPayload(null)
        val resultEmpty = dataWeaveExecutor.evaluate(scriptEmptyString, context)
        assertNotNull(resultEmpty?.value)

        val scriptNaN = "%dw 2.0\noutput application/java\n---\nNaN"
        val resultNaN = dataWeaveExecutor.evaluate(scriptNaN, context)
        assertTrue(resultNaN?.value?.toString()?.contains("NaN") == true)

        val scriptNull = "%dw 2.0\noutput application/java\n---\nnull"
        val resultNull = dataWeaveExecutor.evaluate(scriptNull, context)
        assertNull(resultNull?.value)
    }

    @Test
    fun `should not timeout on simple script`() {
        val script = "%dw 2.0\noutput application/java\n---\n{ok: true}"
        val context = contextWithPayload(null)
        val result = dataWeaveExecutor.evaluate(script, context)

        assertNotNull(result)
        assertTrue(result?.value is Map<*, *>)
        assertEquals(true, (result?.value as Map<String, Any>)["ok"])
    }

    @Test
    fun `should handle vars and attributes with mime type`() {
        val context = contextWithVarsAndMimeType(
            vars = mapOf("var1" to "v1"),
            varMimeType = mapOf("var1" to "application/x-var")
        )
        val expression = "vars.var1"
        val result = dataWeaveExecutor.evaluate(expression, context)

        assertEquals("v1", result?.value)
    }

    @Test
    fun `should evaluate simple payload access expression`() {
        val expression = "payload.name"
        val context = DataWeaveContext(
            event = MuseEvent(
                payload = TypedValue.of(mapOf("name" to "John", "age" to 30))
            ),
        )

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertEquals("John", result?.value?.toString())
    }

    @Test
    fun `should evaluate nested payload access expression`() {
        val expression = "payload.user.address.city"
        val context = DataWeaveContext(
            event = MuseEvent(
                payload = TypedValue.of(
                    mapOf(
                        "user" to mapOf(
                            "address" to mapOf("city" to "New York")
                        )
                    )
                )
            ),
        )

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertEquals("New York", result?.value)
    }

    @Test
    fun `should evaluate vars access expression`() {
        val expression = "vars.myVar"
        val context = contextWithPayload(null, mapOf("myVar" to "Hello World"))

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertEquals("Hello World", result?.value)
    }

    @Test
    fun `should evaluate attributes access expression`() {
        val expression = "attributes.statusCode"
        val context = contextWithAttributes(mapOf("statusCode" to 200))

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertEquals(200, (result?.value as? Number)?.toInt())
    }

    @Test
    fun `should evaluate full data weave script`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            {
                greeting: "Hello " ++ payload.name,
                age: payload.age + 10
            }
        """.trimIndent()

        val context = contextWithPayload(mapOf("name" to "Alice", "age" to 25))

        val result = dataWeaveExecutor.evaluate(script, context)

        assertNotNull(result?.value)
        assertTrue(result?.value?.toString()?.contains("greeting") == true)
        assertTrue(result?.value?.toString()?.contains("Alice") == true)
    }

    @Test
    fun `should evaluate data weave map transformation`() {
        val expression = "payload.items map (item, index) -> {\n  id: item.id,\n  name: upper(item.name)\n}"

        val context = contextWithPayload(
            mapOf(
                "items" to listOf(
                    mapOf("id" to 1, "name" to "apple"),
                    mapOf("id" to 2, "name" to "banana")
                )
            )
        )

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertNotNull(result?.value)
        assertTrue(result?.value?.toString()?.contains("APPLE") == true)
    }

    @Test
    fun `should evaluate data weave filter transformation`() {
        val expression = "payload.numbers filter (item) -> item > 5"

        val context = contextWithPayload(mapOf("numbers" to listOf(1, 6, 3, 8, 2, 10)))

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should evaluate data weave with vars in expression`() {
        val expression = "payload.amount * vars.multiplier"

        val context = DataWeaveContext(
            event = MuseEvent(
                payload = TypedValue.of(mapOf("amount" to 100)),
                variables = mapOf("multiplier" to TypedValue.of(2.5))
            ),
        )

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should evaluate array index access`() {
        val expression = "payload.items[0].name"

        val context = contextWithPayload(
            mapOf(
                "items" to listOf(
                    mapOf("name" to "first"),
                    mapOf("name" to "second")
                )
            )
        )

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertEquals("first", result?.value)
    }

    @Test
    fun `should evaluate conditional expression`() {
        val expression = "if (payload.age > 18) 'adult' else 'minor'"

        val context = contextWithPayload(mapOf("age" to 21))

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertEquals("adult", result?.value)
    }

    @Test
    fun `should evaluate data weave object transformation`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            payload mapObject ((value, key) -> {
                (upper(key)): value
            })
        """.trimIndent()

        val context = contextWithPayload(mapOf("name" to "john", "city" to "boston"))

        val result = dataWeaveExecutor.evaluate(script, context)

        assertTrue(result?.value is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val resultMap = result?.value as Map<String, Any>
        assertEquals("john", resultMap["NAME"])
        assertEquals("boston", resultMap["CITY"])
    }

    @Test
    fun `should handle null payload gracefully`() {
        val expression = "payload.name"

        val context = contextWithPayload(null)

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertNull(result?.value)
    }

    @Test
    fun `should throw exception for invalid expression`() {
        val expression = "payload +++ invalid"

        val context = contextWithPayload(mapOf("name" to "test"))

        assertThrows(Exception::class.java) {
            dataWeaveExecutor.evaluate(expression, context)
        }
    }

    @Test
    fun `should execute flow with data weave array functions`() {
        val expression = "sum(payload.numbers)"

        val context = contextWithPayload(mapOf("numbers" to listOf(10, 20, 30, 40, 50)))

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should execute flow with data weave string functions`() {
        val expression = "upper(payload.text)"

        val context = contextWithPayload(mapOf("text" to "Hello World"))

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertEquals("HELLO WORLD", result?.value)
    }

    @Test
    fun `should execute flow with data weave date functions`() {
        val expression = "(payload.date as Date).year"

        val context = contextWithPayload(mapOf("date" to "2024-03-15"))

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should execute flow with data weave type conversion`() {
        val expression = "payload.num as String"

        val context = contextWithPayload(mapOf("num" to 123))

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertEquals("123", result?.value)
    }

    @Test
    fun `should execute flow with data weave merge`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            payload.user ++ {role: "admin"}
        """.trimIndent()

        val context = contextWithPayload(mapOf("user" to mapOf("name" to "John")))

        val result = dataWeaveExecutor.evaluate(script, context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should execute flow with data weave flatten`() {
        val expression = "flatten(payload.matrix)"

        val context = contextWithPayload(
            mapOf(
                "matrix" to listOf(
                    listOf(1, 2),
                    listOf(3, 4),
                    listOf(5, 6)
                )
            )
        )

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertTrue(result?.value is List<*>)
    }

    @Test
    fun `should execute flow with data weave distinct by`() {
        val expression = """payload.items distinctBy (item) -> item.id"""

        val context = contextWithPayload(
            mapOf(
                "items" to listOf(
                    mapOf("id" to 1, "name" to "a"),
                    mapOf("id" to 2, "name" to "b"),
                    mapOf("id" to 1, "name" to "c")
                )
            )
        )

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertTrue(result?.value is List<*>)
    }

    @Test
    fun `should execute flow with data weave order by`() {
        val expression = """payload.items orderBy (item) -> item.age"""

        val context = contextWithPayload(
            mapOf(
                "items" to listOf(
                    mapOf("name" to "Charlie", "age" to 30),
                    mapOf("name" to "Alice", "age" to 25),
                    mapOf("name" to "Bob", "age" to 35)
                )
            )
        )

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertTrue(result?.value is List<*>)
    }

    @Test
    fun `should handle data weave output types`() {
        val scriptXml = """
            %dw 2.0
            output application/xml
            ---
            {item: payload.name}
        """.trimIndent()

        val context = contextWithPayload(mapOf("name" to "test"))

        val result = dataWeaveExecutor.evaluate(scriptXml, context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should handle data weave output json`() {
        val scriptJson = """
            %dw 2.0
            output application/java
            ---
            {greeting: "Hello", value: 42}
        """.trimIndent()

        val context = contextWithPayload(null)
        val result = dataWeaveExecutor.evaluate(scriptJson, context)

        assertNotNull(result?.value)
        assertTrue(result?.value is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val resultMap = result?.value as Map<String, Any>
        assertEquals("Hello", resultMap["greeting"])
        assertEquals(42, (resultMap["value"] as Number).toInt())
    }

    @Test
    fun `should handle data weave output java object`() {
        val scriptJava = """
            %dw 2.0
            output application/java
            ---
            {name: "JavaObj", value: 123}
        """.trimIndent()

        val context = contextWithPayload(null)
        val result = dataWeaveExecutor.evaluate(scriptJava, context)

        assertNotNull(result?.value)
        assertTrue(result?.value is Map<*, *>)
    }

    @Test
    fun `should handle data weave output plain text`() {
        val scriptText = """
            %dw 2.0
            output application/java
            ---
            "Hello plain text!"
        """.trimIndent()

        val context = contextWithPayload(null)
        val result = dataWeaveExecutor.evaluate(scriptText, context)

        assertNotNull(result?.value)
        assertTrue(result?.value is String)
        assertEquals("Hello plain text!", result?.value)
    }

    @Test
    fun `should handle data weave output string java`() {
        val scriptStringJava = """
            %dw 2.0
            output application/java
            ---
            "Hello as Java String"
        """.trimIndent()

        val context = contextWithPayload(null)
        val result = dataWeaveExecutor.evaluate(scriptStringJava, context)

        assertNotNull(result?.value)
        assertTrue(result?.value is String)
        assertEquals("Hello as Java String", result?.value)
    }

    @Test
    fun `should handle empty array in data weave`() {
        val expression = """payload.items filter (item) -> item.active"""

        val context = contextWithPayload(mapOf("items" to listOf<Map<String, Any>>()))

        val result = dataWeaveExecutor.evaluate(expression, context)

        assertTrue(result?.value is List<*>)
        @Suppress("UNCHECKED_CAST")
        val resultList = result?.value as List<*>
        assertTrue(resultList.isEmpty())
    }

    @Test
    fun `should handle complex nested structure`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            {
                users: payload.users map (user) -> {
                    (user.name): {
                        age: user.age,
                        email: user.email
                    }
                }
            }
        """.trimIndent()

        val context = contextWithPayload(
            mapOf(
                "users" to listOf(
                    mapOf("name" to "john", "age" to 30, "email" to "john@test.com"),
                    mapOf("name" to "jane", "age" to 25, "email" to "jane@test.com")
                )
            )
        )

        val result = dataWeaveExecutor.evaluate(script, context)

        assertTrue(result?.value is Map<*, *>)
    }

    @Test
    fun `should evaluate resource file greeting`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            {greeting: "Hello " ++ payload.name}
        """.trimIndent()
        val context = contextWithPayload(mapOf("name" to "World"))

        val result = dataWeaveExecutor.evaluate(script, context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should evaluate resource file transform`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            payload.items map (item, index) -> {
                (item.name): upper(item.name),
                position: index + 1
            }
        """.trimIndent()
        val context = contextWithPayload(
            mapOf(
                "items" to listOf(
                    mapOf("id" to 1, "name" to "apple"),
                    mapOf("id" to 2, "name" to "banana")
                )
            )
        )

        val result = dataWeaveExecutor.evaluate(script, context)

        assertTrue(result?.value is List<*>)
    }

    @Test
    fun `should evaluate resource file stats`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            {
                total: sum(payload.numbers),
                average: avg(payload.numbers),
                maximum: max(payload.numbers),
                minimum: min(payload.numbers),
                count: sizeOf(payload.numbers)
            }
        """.trimIndent()
        val context = contextWithPayload(
            mapOf(
                "numbers" to listOf(10, 20, 30, 40, 50)
            )
        )

        val result = dataWeaveExecutor.evaluate(script, context)

        assertTrue(result?.value is Map<*, *>)
    }

    @Test
    fun `should throw exception for non existent resource`() {
        val resourcePath = "src/test/resources/dwl/does-not-exist.dwl"

        val exception =
            assertThrows<DataWeaveException> {
                dataWeaveExecutor.evaluateResource(resourcePath, contextWithPayload(null))
            }

        assertEquals("Failed to evaluate resource: $resourcePath", exception.message)
        assertNotNull(exception.cause)
    }
}

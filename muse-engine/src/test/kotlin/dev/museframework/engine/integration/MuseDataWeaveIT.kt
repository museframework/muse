package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.dataweave.DataWeaveContext
import dev.museframework.engine.dataweave.MuseDataWeaveExecutor
import dev.museframework.engine.dataweave.MuseProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mule.weave.v2.model.values.NativeValueProvider
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.sdk.ClassLoaderWeaveResourceResolver
import java.util.*

class MuseDataWeaveIT {

    private lateinit var executor: MuseDataWeaveExecutor

    @BeforeEach
    fun setup() {
        executor = MuseDataWeaveExecutor()
    }

    private fun createContext(
        payload: Any? = null,
        payloadMime: String = TypedValue.MIME_JAVA,
        vars: Map<String, Any> = emptyMap(),
        attributes: Any? = null,
        correlationId: String? = null,
        error: MuseError? = null
    ): DataWeaveContext {
        val event = MuseEvent(
            payload = TypedValue.of(payload),
            variables = vars.mapValues { TypedValue.of(it.value) },
            attributes = attributes?.let { TypedValue.of(it) },
            correlationId = correlationId ?: "test-correlation-id",
            error = error
        )
        return DataWeaveContext.from(event, Properties())
    }

    @Test
    fun `should find MuseRuntimeModule via ServiceLoader`() {
        val providers = ServiceLoader.load(NativeValueProvider::class.java).toList()
        println("Found providers: ${providers.map { it.name() }}")
        assertTrue(providers.any { it.name() == "MuseRuntime" })
    }

    @Test
    fun `should evaluate p() function`() {
        val properties = Properties().apply { setProperty("test.key", "test-value") }
        MuseProperties.set(properties)

        val executor = MuseDataWeaveExecutor()
        val event = MuseEvent(payload = TypedValue.of("test"))
        val context = DataWeaveContext(event = event, properties = properties)

        val result = executor.evaluate("p('test.key')", context)

        assertEquals("test-value", result?.getOrNull())
    }

    @Test
    fun `should find Runtime dwl on classpath`() {
        val url = Thread.currentThread().contextClassLoader
            .getResource("dw/Muse/Runtime.dwl")
        println("Found dwl: $url")
        assertNotNull(url)
    }

    @Test
    fun `should resolve Muse Runtime module`() {
        val resolver = ClassLoaderWeaveResourceResolver.apply()
        val nameId = NameIdentifier.fromFQN("dw::Muse::Runtime")
        val result = resolver.resolve(nameId)
        println("Resolved: $result")
        assertTrue(result.isDefined)
    }

    @Test
    fun `should evaluate simple payload access`() {
        val context = createContext(payload = mapOf("name" to "John", "age" to 30))
        val result = executor.evaluate("payload.name", context)

        assertEquals("John", result?.value)
        assertEquals(TypedValue.MIME_JAVA, result?.mimeType)
    }

    @Test
    fun `should evaluate nested payload access`() {
        val context = createContext(
            payload = mapOf(
                "user" to mapOf(
                    "address" to mapOf("city" to "New York")
                )
            )
        )
        val result = executor.evaluate("payload.user.address.city", context)

        assertEquals("New York", result?.value)
    }

    @Test
    fun `should evaluate vars access`() {
        val context = createContext(
            payload = null,
            vars = mapOf("myVar" to "Hello World")
        )
        val result = executor.evaluate("vars.myVar", context)

        assertEquals("Hello World", result?.value)
    }

    @Test
    fun `should evaluate multiple vars`() {
        val context = createContext(
            payload = null,
            vars = mapOf(
                "name" to "Alice",
                "age" to 25,
                "active" to true
            )
        )

        assertEquals("Alice", executor.evaluate("vars.name", context)?.value)
        assertEquals(25, executor.evaluate("vars.age", context)?.value)
        assertEquals(true, executor.evaluate("vars.active", context)?.value)
    }

    @Test
    fun `should evaluate attributes access`() {
        val context = createContext(
            payload = null,
            attributes = mapOf("statusCode" to 200, "headers" to mapOf("content-type" to "application/json"))
        )
        val result = executor.evaluate("attributes.statusCode", context)

        assertEquals(200, (result?.value as? Number)?.toInt())
    }

    @Test
    fun `should access correlationId`() {
        val context = createContext(correlationId = "custom-correlation-123")
        val result = executor.evaluate("correlationId", context)

        assertEquals("custom-correlation-123", result?.value)
    }

    @Test
    fun `should handle null payload`() {
        val context = createContext(payload = null)
        val result = executor.evaluate("payload", context)

        assertNull(result?.value)
    }

    @Test
    fun `should handle missing key in payload`() {
        val context = createContext(payload = mapOf("exists" to "value"))
        val result = executor.evaluate("payload.missing", context)

        assertNull(result?.value)
    }

    @Test
    fun `should evaluate arithmetic operations`() {
        val context = createContext(payload = mapOf("a" to 10, "b" to 5))

        assertEquals(15, executor.evaluate("payload.a + payload.b", context)?.value)
        assertEquals(5, executor.evaluate("payload.a - payload.b", context)?.value)
        assertEquals(50, executor.evaluate("payload.a * payload.b", context)?.value)
        assertEquals(2, executor.evaluate("payload.a / payload.b", context)?.value)
    }

    @Test
    fun `should evaluate string operations`() {
        val context = createContext(payload = mapOf("first" to "Hello", "second" to "World"))

        assertEquals("HelloWorld", executor.evaluate("payload.first ++ payload.second", context)?.value)
        assertEquals("HELLO", executor.evaluate("upper(payload.first)", context)?.value)
        assertEquals("hello", executor.evaluate("lower(payload.first)", context)?.value)
        assertEquals(5, executor.evaluate("sizeOf(payload.first)", context)?.value)
    }

    @Test
    fun `should evaluate conditional expressions`() {
        val context = createContext(payload = mapOf("age" to 21))

        assertEquals("adult", executor.evaluate("if (payload.age > 18) 'adult' else 'minor'", context)?.value)

        val contextMinor = createContext(payload = mapOf("age" to 15))
        assertEquals("minor", executor.evaluate("if (payload.age > 18) 'adult' else 'minor'", contextMinor)?.value)
    }

    @Test
    fun `should evaluate map function`() {
        val context = createContext(
            payload = mapOf(
                "items" to listOf(
                    mapOf("id" to 1, "name" to "apple"),
                    mapOf("id" to 2, "name" to "banana")
                )
            )
        )
        val result = executor.evaluate("payload.items map (item) -> {id: item.id, name: upper(item.name)}", context)

        assertNotNull(result?.value)
        @Suppress("UNCHECKED_CAST")
        val list = result?.value as List<Map<String, Any>>
        assertEquals(2, list.size)
        assertEquals("APPLE", list[0]["name"])
        assertEquals("BANANA", list[1]["name"])
    }

    @Test
    fun `should evaluate filter function`() {
        val context = createContext(
            payload = mapOf("numbers" to listOf(1, 6, 3, 8, 2, 10))
        )
        val result = executor.evaluate("payload.numbers filter (item) -> item > 5", context)

        assertNotNull(result?.value)
        @Suppress("UNCHECKED_CAST")
        val list = result?.value as List<Int>
        assertEquals(3, list.size)
        assertTrue(list.contains(6))
        assertTrue(list.contains(8))
        assertTrue(list.contains(10))
    }

    @Test
    fun `should evaluate reduce function`() {
        val context = createContext(payload = mapOf("numbers" to listOf(1, 2, 3, 4, 5)))
        val result = executor.evaluate("payload.numbers reduce ($$ + $)", context)

        assertEquals(15, result?.value)
    }

    @Test
    fun `should evaluate array index access`() {
        val context = createContext(
            payload = mapOf(
                "items" to listOf("first", "second", "third")
            )
        )

        assertEquals("first", executor.evaluate("payload.items[0]", context)?.value)
        assertEquals("second", executor.evaluate("payload.items[1]", context)?.value)
        assertEquals("third", executor.evaluate("payload.items[2]", context)?.value)
    }

    @Test
    fun `should return java objects with java output`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            {name: "test", count: 42}
        """.trimIndent()
        val context = createContext()
        val result = executor.evaluate(script, context)

        assertTrue(result?.value is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val map = result?.value as Map<String, Any>
        assertEquals("test", map["name"])
        assertEquals(42, (map["count"] as Number).toInt())
        assertEquals(TypedValue.MIME_JAVA, result?.mimeType)
    }

    @Test
    fun `should return different mime types in result`() {
        val javaScript = """
            %dw 2.0
            output application/java
            ---
            {key: "value"}
        """.trimIndent()

        val javaResult = executor.evaluate(javaScript, createContext())
        assertEquals(TypedValue.MIME_JAVA, javaResult?.mimeType)
        assertTrue(javaResult?.value is Map<*, *>)
    }

    @Test
    fun `should handle flatten function`() {
        val context = createContext(
            payload = mapOf(
                "matrix" to listOf(
                    listOf(1, 2),
                    listOf(3, 4),
                    listOf(5, 6)
                )
            )
        )
        val result = executor.evaluate("flatten(payload.matrix)", context)

        assertNotNull(result?.value)
        @Suppress("UNCHECKED_CAST")
        val list = result?.value as List<Int>
        assertEquals(listOf(1, 2, 3, 4, 5, 6), list)
    }

    @Test
    fun `should handle distinctBy function`() {
        val context = createContext(
            payload = mapOf(
                "items" to listOf(
                    mapOf("id" to 1, "name" to "a"),
                    mapOf("id" to 2, "name" to "b"),
                    mapOf("id" to 1, "name" to "c")
                )
            )
        )
        val result = executor.evaluate("payload.items distinctBy (item) -> item.id", context)

        assertNotNull(result?.value)
        @Suppress("UNCHECKED_CAST")
        val list = result?.value as List<Map<String, Any>>
        assertEquals(2, list.size)
    }

    @Test
    fun `should handle orderBy function`() {
        val context = createContext(
            payload = mapOf(
                "items" to listOf(
                    mapOf("name" to "Charlie", "age" to 30),
                    mapOf("name" to "Alice", "age" to 25),
                    mapOf("name" to "Bob", "age" to 35)
                )
            )
        )
        val result = executor.evaluate("payload.items orderBy (item) -> item.age", context)

        assertNotNull(result?.value)
        @Suppress("UNCHECKED_CAST")
        val list = result?.value as List<Map<String, Any>>
        assertEquals("Alice", list[0]["name"])
        assertEquals("Charlie", list[1]["name"])
        assertEquals("Bob", list[2]["name"])
    }

    @Test
    fun `should handle pluck function`() {
        val context = createContext(
            payload = mapOf(
                "users" to mapOf(
                    "Max" to mapOf("role" to "admin"),
                    "Einstein" to mapOf("role" to "dev")
                )
            )
        )
        val result = executor.evaluate("payload.users pluck ((value, key) -> {name: key, role: value.role})", context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should handle type coercion`() {
        val context = createContext(payload = mapOf("num" to "123", "str" to "hello"))

        assertEquals(123, executor.evaluate("payload.num as Number", context)?.value)
        assertEquals("hello", executor.evaluate("payload.str as String", context)?.value)
    }

    @Test
    fun `should handle default output when none specified`() {
        val script = """
            %dw 2.0
            ---
            {key: "value"}
        """.trimIndent()

        val context = createContext()
        val result = executor.evaluate(script, context)

        assertNotNull(result?.value)
    }

    @Test
    fun `should propagate error binding when present`() {
        val error = MuseError(
            description = "Test error",
            detailedDescription = "Detailed test error",
            errorType = ErrorType.Mule.ANY,
            cause = RuntimeException("Original cause"),
            errorMessage = "Error message",
            childErrors = emptyList()
        )
        val context = createContext(error = error)
        val result = executor.evaluate("error.description", context)

        assertEquals("Test error", result?.value)
    }

    @Test
    fun `should handle empty array in filter`() {
        val context = createContext(payload = mapOf("items" to listOf<Map<String, Any>>()))
        val result = executor.evaluate("payload.items filter (item) -> item.active", context)

        assertNotNull(result?.value)
        @Suppress("UNCHECKED_CAST")
        val list = result?.value as List<*>
        assertTrue(list.isEmpty())
    }

    @Test
    fun `should handle complex nested transformations`() {
        val script = """
            %dw 2.0
            output application/java
            ---
            payload.users map (user) -> {
                (user.name): {
                    age: user.age,
                    email: user.email
                }
            }
        """.trimIndent()

        val context = createContext(
            payload = mapOf(
                "users" to listOf(
                    mapOf("name" to "john", "age" to 30, "email" to "john@test.com"),
                    mapOf("name" to "jane", "age" to 25, "email" to "jane@test.com")
                )
            )
        )

        val result = executor.evaluate(script, context)

        assertTrue(result?.value is List<*>)
    }

    @Test
    fun `should handle sizeOf on different types`() {
        val context = createContext(
            payload = mapOf(
                "string" to "hello",
                "array" to listOf(1, 2, 3),
                "object" to mapOf("a" to 1, "b" to 2)
            )
        )

        assertEquals(5, executor.evaluate("sizeOf(payload.string)", context)?.value)
        assertEquals(3, executor.evaluate("sizeOf(payload.array)", context)?.value)
        assertEquals(2, executor.evaluate("sizeOf(payload.object)", context)?.value)
    }

    @Test
    fun `should handle isBlank and isEmpty`() {
        val context = createContext(
            payload = mapOf(
                "emptyString" to "",
                "nullValue" to null,
                "hasValue" to "test"
            )
        )

        assertEquals(true, executor.evaluate("isBlank(payload.emptyString)", context)?.value)
        assertEquals(true, executor.evaluate("isBlank(payload.nullValue)", context)?.value)
        assertEquals(false, executor.evaluate("isBlank(payload.hasValue)", context)?.value)
    }

    @Test
    fun `should handle logical operators`() {
        val context = createContext(payload = mapOf("a" to true, "b" to false))

        assertEquals(false, executor.evaluate("payload.a and payload.b", context)?.value)
        assertEquals(true, executor.evaluate("payload.a or payload.b", context)?.value)
        assertEquals(false, executor.evaluate("not payload.a", context)?.value)
    }

    @Test
    fun `should handle math functions`() {
        val context = createContext(payload = mapOf("numbers" to listOf(10, 20, 30, 40, 50)))

        assertEquals(150.0, (executor.evaluate("sum(payload.numbers)", context)?.value as? Number)?.toDouble())
        assertEquals(30.0, (executor.evaluate("avg(payload.numbers)", context)?.value as? Number)?.toDouble())
        assertEquals(50, executor.evaluate("max(payload.numbers)", context)?.value)
        assertEquals(10, executor.evaluate("min(payload.numbers)", context)?.value)
    }

    @Test
    fun `should handle object merge with ++`() {
        val context = createContext(payload = mapOf("user" to mapOf("name" to "John")))
        val script = """
            %dw 2.0
            output application/java
            ---
            payload.user ++ {role: "admin"}
        """.trimIndent()

        val result = executor.evaluate(script, context)

        assertNotNull(result?.value)
        @Suppress("UNCHECKED_CAST")
        val map = result?.value as Map<String, Any>
        assertEquals("John", map["name"])
        assertEquals("admin", map["role"])
    }

    @Test
    fun `should handle mapObject function`() {
        val context = createContext(payload = mapOf("name" to "john", "city" to "boston"))
        val script = """
            %dw 2.0
            output application/java
            ---
            payload mapObject ((value, key) -> {
                (upper(key)): value
            })
        """.trimIndent()

        val result = executor.evaluate(script, context)

        assertTrue(result?.value is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val map = result?.value as Map<String, Any>
        assertEquals("john", map["NAME"])
        assertEquals("boston", map["CITY"])
    }

    @Test
    fun `should handle nested map and filter`() {
        val context = createContext(
            payload = mapOf(
                "departments" to listOf(
                    mapOf(
                        "name" to "IT", "employees" to listOf(
                            mapOf("name" to "Alice", "salary" to 80000),
                            mapOf("name" to "Bob", "salary" to 50000)
                        )
                    ),
                    mapOf(
                        "name" to "HR", "employees" to listOf(
                            mapOf("name" to "Charlie", "salary" to 60000)
                        )
                    )
                )
            )
        )
        val result = executor.evaluate(
            "payload.departments map (dept) -> {name: dept.name, highEarners: (dept.employees filter ($.salary > 70000))}",
            context
        )

        assertNotNull(result?.value)
    }
}

package dev.museframework.http.api.dsl

import dev.museframework.http.api.config.*
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.dsl.api.model.config.ConfigRef
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HttpRequestBuilderTest {

    private val defaultConnectionConfig =
        ConfigRef.ByReference(
            HttpRequestConnectionConfig(
                docName = "test-connection",
                host = "localhost",
                port = 8080,
            ),
        )

    @Test
    fun `should build HttpRequestConfig with defaults`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig)
        val config = builder.build()

        assertNotNull(config.docId)
        assertEquals("", config.docName)
        assertEquals("/", config.path)
        assertNull(config.url)
        assertEquals(HttpMethod.GET, config.method)
        assertTrue(config.headers.isEmpty())
        assertTrue(config.queryParams.isEmpty())
        assertTrue(config.uriParams.isEmpty())
        assertNull(config.body)
        assertNull(config.responseTimeoutMs)
        assertNull(config.followRedirects)
        assertNull(config.sendBodyMode)
        assertNull(config.requestStreamingMode)
        assertNull(config.sendCorrelationId)
        assertNull(config.correlationId)
        assertNull(config.targetConfig)
        assertTrue(config.responseValidator is DefaultResponseValidator)
        assertEquals(defaultConnectionConfig, config.connectionConfig)
    }

    @Test
    fun `should build HttpRequestConfig with custom docId and docName`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            docId = "custom-doc-id"
            docName = "custom-request"
        }
        val config = builder.build()

        assertEquals("custom-doc-id", config.docId)
        assertEquals("custom-request", config.docName)
    }

    @Test
    fun `should build HttpRequestConfig with custom path`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            path = "/api/users"
        }
        val config = builder.build()

        assertEquals("/api/users", config.path)
    }

    @Test
    fun `should build HttpRequestConfig with custom method`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            method = HttpMethod.POST
        }
        val config = builder.build()

        assertEquals(HttpMethod.POST, config.method)
    }

    @Test
    fun `should build HttpRequestConfig with headers`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            header("Content-Type", "application/json")
            header("Authorization", "Bearer token")
        }
        val config = builder.build()

        assertEquals("application/json", config.headers["Content-Type"])
        assertEquals("Bearer token", config.headers["Authorization"])
        assertEquals(2, config.headers.size)
    }

    @Test
    fun `should build HttpRequestConfig with query params`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            queryParam("page", "1")
            queryParam("limit", "10")
        }
        val config = builder.build()

        assertEquals("1", config.queryParams["page"])
        assertEquals("10", config.queryParams["limit"])
        assertEquals(2, config.queryParams.size)
    }

    @Test
    fun `should build URL URI and expression parameters`() {
        val config =
            HttpRequestBuilder(defaultConnectionConfig).apply {
                url = "https://api.example.test/orders/{id}"
                uriParam("id", "#[vars.orderId]")
                headersExpression = "#[vars.headers]"
                queryParamsExpression = "#[vars.query]"
                uriParamsExpression = "#[vars.uri]"
                sendBodyMode = HttpSendBodyMode.ALWAYS
                requestStreamingMode = HttpStreamingMode.NEVER
                correlationId = "#[vars.correlationId]"
            }.build()

        assertEquals("https://api.example.test/orders/{id}", config.url)
        assertEquals(mapOf("id" to "#[vars.orderId]"), config.uriParams)
        assertEquals("#[vars.headers]", config.headersExpression)
        assertEquals("#[vars.query]", config.queryParamsExpression)
        assertEquals("#[vars.uri]", config.uriParamsExpression)
        assertEquals(HttpSendBodyMode.ALWAYS, config.sendBodyMode)
        assertEquals(HttpStreamingMode.NEVER, config.requestStreamingMode)
        assertEquals("#[vars.correlationId]", config.correlationId)
    }

    @Test
    fun `should build HttpRequestConfig with body`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            body = """{"name":"test"}"""
        }
        val config = builder.build()

        assertEquals("""{"name":"test"}""", config.body)
    }

    @Test
    fun `should build HttpRequestConfig with custom responseTimeoutMs`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            responseTimeoutMs = 5000
        }
        val config = builder.build()

        assertEquals(5000, config.responseTimeoutMs)
    }

    @Test
    fun `should build HttpRequestConfig with custom followRedirects`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            followRedirects = false
        }
        val config = builder.build()

        assertEquals(false, config.followRedirects)
    }

    @Test
    fun `should build HttpRequestConfig with custom sendCorrelationId`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            sendCorrelationId = SendCorrelationId.ALWAYS
        }
        val config = builder.build()

        assertEquals(SendCorrelationId.ALWAYS, config.sendCorrelationId)
    }

    @Test
    fun `should build HttpRequestConfig with NEVER sendCorrelationId`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            sendCorrelationId = SendCorrelationId.NEVER
        }
        val config = builder.build()

        assertEquals(SendCorrelationId.NEVER, config.sendCorrelationId)
    }

    @Test
    fun `should build HttpRequestConfig with custom responseValidator`() {
        val customValidator = SuccessStatusCodeValidator(200..201)
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            responseValidator = customValidator
        }
        val config = builder.build()

        assertEquals(customValidator, config.responseValidator)
    }

    @Test
    fun `should preserve connection config in built HttpRequestConfig`() {
        val customConnectionConfig = HttpRequestConnectionConfig(
            docName = "custom-connection",
            host = "127.0.0.1",
            port = 9090,
            basePath = "/api",
        )
        val connectionRef = ConfigRef.ByReference(customConnectionConfig)
        val builder = HttpRequestBuilder(connectionRef)
        val config = builder.build()

        assertEquals(connectionRef, config.connectionConfig)
        assertEquals(customConnectionConfig, (config.connectionConfig as ConfigRef.ByReference).config)
    }

    @Test
    fun `should support builder pattern with multiple properties`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            docId = "multi-prop-id"
            docName = "multi-prop-request"
            path = "/api/users"
            method = HttpMethod.PUT
            header("Content-Type", "application/json")
            queryParam("id", "123")
            body = """{"name":"updated"}"""
            responseTimeoutMs = 10000
            followRedirects = true
            sendCorrelationId = SendCorrelationId.ALWAYS
        }
        val config = builder.build()

        assertEquals("multi-prop-id", config.docId)
        assertEquals("multi-prop-request", config.docName)
        assertEquals("/api/users", config.path)
        assertEquals(HttpMethod.PUT, config.method)
        assertEquals("application/json", config.headers["Content-Type"])
        assertEquals("123", config.queryParams["id"])
        assertEquals("""{"name":"updated"}""", config.body)
        assertEquals(10000, config.responseTimeoutMs)
        assertEquals(true, config.followRedirects)
        assertEquals(SendCorrelationId.ALWAYS, config.sendCorrelationId)
    }

    @Test
    fun `should generate unique docId by default`() {
        val builder1 = HttpRequestBuilder(defaultConnectionConfig)
        val builder2 = HttpRequestBuilder(defaultConnectionConfig)
        val config1 = builder1.build()
        val config2 = builder2.build()

        assertNotEquals(config1.docId, config2.docId)
        assertNotNull(config1.docId)
        assertNotNull(config2.docId)
    }

    @Test
    fun `should support all HTTP methods`() {
        HttpMethod.values().filter { it != HttpMethod.ANY }.forEach { method ->
            val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
                this.method = method
            }
            val config = builder.build()
            assertEquals(method, config.method, "Method $method should be supported")
        }
    }

    @Test
    fun `should support empty headers and queryParams`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig)
        val config = builder.build()

        assertTrue(config.headers.isEmpty())
        assertTrue(config.queryParams.isEmpty())
    }

    @Test
    fun `should override header with same name`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            header("X-Header", "value1")
            header("X-Header", "value2")
        }
        val config = builder.build()

        assertEquals("value2", config.headers["X-Header"])
        assertEquals(1, config.headers.size)
    }

    @Test
    fun `should override queryParam with same name`() {
        val builder = HttpRequestBuilder(defaultConnectionConfig).apply {
            queryParam("key", "value1")
            queryParam("key", "value2")
        }
        val config = builder.build()

        assertEquals("value2", config.queryParams["key"])
        assertEquals(1, config.queryParams.size)
    }
}

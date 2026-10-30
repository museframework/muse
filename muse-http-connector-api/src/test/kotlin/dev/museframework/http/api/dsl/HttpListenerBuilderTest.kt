package dev.museframework.http.api.dsl

import dev.museframework.http.api.config.ConnectionConfigRef
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpListenerLifecycle
import dev.museframework.http.api.config.SuccessHttpResponseConfig
import dev.museframework.http.api.config.ErrorHttpResponseConfig
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.dsl.HttpListenerBuilder
import dev.museframework.http.api.model.HttpMethod
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HttpListenerBuilderTest {

    private val defaultConnectionConfig = HttpListenerConnectionConfig(
        name ="test_connection",
        docName = "testconnection",
        host = "localhost",
        port = 8081,
    )

    @Test
    fun `should build HttpListenerConfig with defaults`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig))
        val config = builder.build()

        assertNotNull(config.docId)
        assertEquals("", config.docName)
        assertEquals("/", config.path)
        assertEquals(listOf(HttpMethod.ANY), config.allowedMethods)
        assertNull(config.timeoutMs)
        assertEquals(HttpStreamingMode.AUTO, config.responseStreamingMode)
        assertEquals(HttpListenerLifecycle.EAGER, config.lifecycle)
        assertEquals(defaultConnectionConfig, config.connectionConfig)
    }

    @Test
    fun `should build HttpListenerConfig with custom docId and docName`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            docId = "custom-doc-id"
            docName = "custom-listener"
        }
        val config = builder.build()

        assertEquals("custom-doc-id", config.docId)
        assertEquals("custom-listener", config.docName)
    }

    @Test
    fun `should build HttpListenerConfig with custom path`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            path = "/api/users"
        }
        val config = builder.build()

        assertEquals("/api/users", config.path)
    }

    @Test
    fun `should build HttpListenerConfig with custom allowed methods`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST)
        }
        val config = builder.build()

        assertEquals(listOf(HttpMethod.GET, HttpMethod.POST), config.allowedMethods)
    }

    @Test
    fun `should build HttpListenerConfig with single allowed method`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            allowedMethods = listOf(HttpMethod.DELETE)
        }
        val config = builder.build()

        assertEquals(listOf(HttpMethod.DELETE), config.allowedMethods)
    }

    @Test
    fun `should build HttpListenerConfig with custom timeout`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            timeoutMs = 5000
        }
        val config = builder.build()

        assertEquals(5000, config.timeoutMs)
    }

    @Test
    fun `should build HttpListenerConfig with null timeout by default`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig))
        val config = builder.build()

        assertNull(config.timeoutMs)
    }

    @Test
    fun `should build HttpListenerConfig with EAGER lifecycle by default`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig))
        val config = builder.build()

        assertEquals(HttpListenerLifecycle.EAGER, config.lifecycle)
    }

    @Test
    fun `should build HttpListenerConfig with LAZY lifecycle`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            lifecycle = HttpListenerLifecycle.LAZY
        }
        val config = builder.build()

        assertEquals(HttpListenerLifecycle.LAZY, config.lifecycle)
    }

    @Test
    fun `should build HttpListenerConfig with custom success response`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            successResponse {
                statusCode = "202"
                reasonPhrase = "Accepted"
                headersExpression = "#[vars.responseHeaders]"
                header("X-Custom-Header", "value")
                body = """{"status":"accepted"}"""
            }
        }
        val config = builder.build()

        val successResponse = config.successResponse as SuccessHttpResponseConfig
        assertEquals("202", successResponse.statusCode)
        assertEquals("Accepted", successResponse.reasonPhrase)
        assertEquals("#[vars.responseHeaders]", successResponse.headersExpression)
        assertEquals(mapOf("X-Custom-Header" to "value"), successResponse.headers)
        assertEquals("""{"status":"accepted"}""", successResponse.body)
    }

    @Test
    fun `should build HttpListenerConfig with custom error response`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            errorResponse {
                statusCode = "#[vars.httpStatus default 400]"
                reasonPhrase = "#[error.description]"
                headersExpression = "#[vars.errorHeaders]"
                header("X-Error-ID", "#[correlationId]")
                body = """{"error":"#[error.description]"}"""
            }
        }
        val config = builder.build()

        val errorResponse = config.errorResponse as ErrorHttpResponseConfig
        assertEquals("#[vars.httpStatus default 400]", errorResponse.statusCode)
        assertEquals("#[error.description]", errorResponse.reasonPhrase)
        assertEquals("#[vars.errorHeaders]", errorResponse.headersExpression)
        assertEquals(mapOf("X-Error-ID" to "#[correlationId]"), errorResponse.headers)
        assertEquals("""{"error":"#[error.description]"}""", errorResponse.body)
    }

    @Test
    fun `should build HttpListenerConfig with default success response`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig))
        val config = builder.build()

        val successResponse = config.successResponse as SuccessHttpResponseConfig
        assertEquals("#[vars.httpStatus default 200]", successResponse.statusCode)
        assertTrue(successResponse.headers.isEmpty())
        assertNull(successResponse.body)
        assertNull(successResponse.reasonPhrase)
        assertNull(successResponse.headersExpression)
    }

    @Test
    fun `should build HttpListenerConfig with default error response`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig))
        val config = builder.build()

        val errorResponse = config.errorResponse as ErrorHttpResponseConfig
        assertEquals("#[vars.httpStatus default 500]", errorResponse.statusCode)
        assertTrue(errorResponse.headers.isEmpty())
        assertNull(errorResponse.body)
        assertNull(errorResponse.reasonPhrase)
        assertNull(errorResponse.headersExpression)
    }

    @Test
    fun `should build listener response streaming mode`() {
        val config =
            HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig))
                .apply { responseStreamingMode = HttpStreamingMode.ALWAYS }
                .build()

        assertEquals(HttpStreamingMode.ALWAYS, config.responseStreamingMode)
    }

    @Test
    fun `should preserve connection config in built HttpListenerConfig`() {
        val customConnectionConfig = HttpListenerConnectionConfig(
            name= "Custom_Connection",
            docName = "custom connection",
            host = "127.0.0.1",
            port = 9090,
            basePath = "/api",
        )
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(customConnectionConfig))
        val config = builder.build()

        assertEquals(customConnectionConfig, config.connectionConfig)
        assertEquals("127.0.0.1", config.connectionConfig.host)
        assertEquals(9090, config.connectionConfig.port)
        assertEquals("/api", config.connectionConfig.basePath)
    }

    @Test
    fun `should support builder pattern with multiple properties`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            docId = "multi-prop-id"
            docName = "multi-prop-listener"
            path = "/api/webhooks"
            allowedMethods = listOf(HttpMethod.POST, HttpMethod.PUT)
            timeoutMs = 10000
            lifecycle = HttpListenerLifecycle.LAZY
        }
        val config = builder.build()

        assertEquals("multi-prop-id", config.docId)
        assertEquals("multi-prop-listener", config.docName)
        assertEquals("/api/webhooks", config.path)
        assertEquals(listOf(HttpMethod.POST, HttpMethod.PUT), config.allowedMethods)
        assertEquals(10000, config.timeoutMs)
        assertEquals(HttpListenerLifecycle.LAZY, config.lifecycle)
    }

    @Test
    fun `should generate unique docId by default`() {
        val builder1 = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig))
        val builder2 = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig))
        val config1 = builder1.build()
        val config2 = builder2.build()

        assertNotEquals(config1.docId, config2.docId)
        assertNotNull(config1.docId)
        assertNotNull(config2.docId)
    }

    @Test
    fun `should support empty allowed methods list`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            allowedMethods = emptyList()
        }
        val config = builder.build()

        assertTrue(config.allowedMethods.isEmpty())
    }

    @Test
    fun `should support all HTTP methods in allowed methods`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByReference(defaultConnectionConfig)).apply {
            allowedMethods = listOf(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.DELETE,
                HttpMethod.PATCH,
                HttpMethod.HEAD,
                HttpMethod.OPTIONS,
            )
        }
        val config = builder.build()

        assertEquals(7, config.allowedMethods.size)
        assertTrue(config.allowedMethods.contains(HttpMethod.GET))
        assertTrue(config.allowedMethods.contains(HttpMethod.POST))
        assertTrue(config.allowedMethods.contains(HttpMethod.PUT))
        assertTrue(config.allowedMethods.contains(HttpMethod.DELETE))
        assertTrue(config.allowedMethods.contains(HttpMethod.PATCH))
        assertTrue(config.allowedMethods.contains(HttpMethod.HEAD))
        assertTrue(config.allowedMethods.contains(HttpMethod.OPTIONS))
    }

    @Test
    fun `should build HttpListenerConfig with ByName connectionConfigRef`() {
        val builder = HttpListenerBuilder(ConnectionConfigRef.ByName("my-http-listener")).apply {
            path = "/api/orders"
        }
        val config = builder.build()

        assertEquals(ConnectionConfigRef.ByName("my-http-listener"), config.connectionConfigRef)
        assertEquals("/api/orders", config.path)
    }
}

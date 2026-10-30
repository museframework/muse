package dev.museframework.http.api.dsl

import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.SendCorrelationId
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.dsl.flow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HttpRequestDslTest {

    private fun createConnectionConfig(): HttpRequestConnectionConfig =
        HttpRequestConnectionConfig(
            docName = "test-connection",
            host = "localhost",
            port = 8080,
        )

    @Test
    fun `DSL should create HttpRequestConfig in flow pipeline with direct config`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                path = "/api/users"
                method = HttpMethod.GET
            }
        }

        assertEquals(1, testFlow.pipeline.size)
        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals("/api/users", config.path)
        assertEquals(HttpMethod.GET, config.method)
        assertEquals(ConfigRef.ByReference(connectionConfig), config.connectionConfig)
    }

    @Test
    fun `DSL should create HttpRequestConfig in flow pipeline with lambda config`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest({ connectionConfig }) {
                path = "/api/posts"
                method = HttpMethod.POST
            }
        }

        assertEquals(1, testFlow.pipeline.size)
        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals("/api/posts", config.path)
        assertEquals(HttpMethod.POST, config.method)
        assertEquals(ConfigRef.ByReference(connectionConfig), config.connectionConfig)
    }

    @Test
    fun `DSL should support custom docId and docName`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                docId = "custom-request-id"
                docName = "Get Users Request"
                path = "/api/users"
            }
        }

        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals("custom-request-id", config.docId)
        assertEquals("Get Users Request", config.docName)
    }

    @Test
    fun `DSL should support headers`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                path = "/api/users"
                header("Content-Type", "application/json")
                header("Authorization", "Bearer token123")
            }
        }

        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals("application/json", config.headers["Content-Type"])
        assertEquals("Bearer token123", config.headers["Authorization"])
        assertEquals(2, config.headers.size)
    }

    @Test
    fun `DSL should support query params`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                path = "/api/users"
                queryParam("page", "1")
                queryParam("limit", "10")
            }
        }

        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals("1", config.queryParams["page"])
        assertEquals("10", config.queryParams["limit"])
        assertEquals(2, config.queryParams.size)
    }

    @Test
    fun `DSL should support body`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                path = "/api/users"
                method = HttpMethod.POST
                body = """{"name":"John","email":"john@example.com"}"""
            }
        }

        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals("""{"name":"John","email":"john@example.com"}""", config.body)
    }

    @Test
    fun `DSL should support custom responseTimeoutMs`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                path = "/api/users"
                responseTimeoutMs = 5000
            }
        }

        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals(5000, config.responseTimeoutMs)
    }

    @Test
    fun `DSL should support custom followRedirects`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                path = "/api/users"
                followRedirects = false
            }
        }

        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals(false, config.followRedirects)
    }

    @Test
    fun `DSL should support custom sendCorrelationId`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                path = "/api/users"
                sendCorrelationId = SendCorrelationId.ALWAYS
            }
        }

        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals(SendCorrelationId.ALWAYS, config.sendCorrelationId)
    }

    @Test
    fun `DSL should support multiple httpRequest calls`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                path = "/api/users"
                method = HttpMethod.GET
            }
            httpRequest(connectionConfig) {
                path = "/api/posts"
                method = HttpMethod.POST
            }
        }

        assertEquals(2, testFlow.pipeline.size)
        val config1 = testFlow.pipeline[0] as HttpRequestConfig
        val config2 = testFlow.pipeline[1] as HttpRequestConfig
        assertEquals("/api/users", config1.path)
        assertEquals(HttpMethod.GET, config1.method)
        assertEquals("/api/posts", config2.path)
        assertEquals(HttpMethod.POST, config2.method)
    }

    @Test
    fun `DSL should generate unique docId by default`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                path = "/api/users"
            }
            httpRequest(connectionConfig) {
                path = "/api/posts"
            }
        }

        val config1 = testFlow.pipeline[0] as HttpRequestConfig
        val config2 = testFlow.pipeline[1] as HttpRequestConfig
        assertNotNull(config1.docId)
        assertNotNull(config2.docId)
        assertNotEquals(config1.docId, config2.docId)
    }

    @Test
    fun `DSL should support all HTTP methods`() {
        val connectionConfig = createConnectionConfig()
        val methods = listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)

        methods.forEach { method ->
            val testFlow = flow("test-flow-$method") {
                httpRequest(connectionConfig) {
                    path = "/api/test"
                    this.method = method
                }
            }

            val config = testFlow.pipeline[0] as HttpRequestConfig
            assertEquals(method, config.method, "Method $method should be supported")
        }
    }

    @Test
    fun `DSL should support complex configuration`() {
        val connectionConfig = createConnectionConfig()
        val testFlow = flow("test-flow") {
            httpRequest(connectionConfig) {
                docId = "complex-request-id"
                docName = "Complex API Request"
                path = "/api/v2/resources"
                method = HttpMethod.PUT
                header("Content-Type", "application/json")
                header("X-API-Key", "secret-key")
                queryParam("format", "json")
                body = """{"data":"value"}"""
                responseTimeoutMs = 10000
                followRedirects = true
                sendCorrelationId = SendCorrelationId.NEVER
            }
        }

        val config = testFlow.pipeline[0] as HttpRequestConfig
        assertEquals("complex-request-id", config.docId)
        assertEquals("Complex API Request", config.docName)
        assertEquals("/api/v2/resources", config.path)
        assertEquals(HttpMethod.PUT, config.method)
        assertEquals("application/json", config.headers["Content-Type"])
        assertEquals("secret-key", config.headers["X-API-Key"])
        assertEquals("json", config.queryParams["format"])
        assertEquals("""{"data":"value"}""", config.body)
        assertEquals(10000, config.responseTimeoutMs)
        assertEquals(true, config.followRedirects)
        assertEquals(SendCorrelationId.NEVER, config.sendCorrelationId)
    }
}

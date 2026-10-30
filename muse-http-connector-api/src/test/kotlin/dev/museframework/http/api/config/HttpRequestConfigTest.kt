package dev.museframework.http.api.config

import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRequestConfigTest {

    private fun createConnectionConfig(): ConfigRef =
        ConfigRef.ByReference(
            HttpRequestConnectionConfig(
                host = "localhost",
                port = 8080,
                protocol = HttpProtocol.HTTP,
            ),
        )

    @Test
    fun `should create config with required fields only`() {
        val connectionConfig = createConnectionConfig()
        val config = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test"
        )

        assertNotNull(config.docId)
        assertTrue(config.docId.isNotEmpty())
        assertEquals("", config.docName)
        assertNull(config.docDescription)
        assertNull(config.sourceFile)
        assertNull(config.sourceLine)
        assertNull(config.sourceClass)
        assertEquals(connectionConfig, config.connectionConfig)
        assertEquals("/api/test", config.path)
        assertEquals(HttpMethod.GET, config.method)
        assertNull(config.url)
        assertEquals(emptyMap(), config.headers)
        assertNull(config.headersExpression)
        assertEquals(emptyMap(), config.queryParams)
        assertNull(config.queryParamsExpression)
        assertEquals(emptyMap(), config.uriParams)
        assertNull(config.uriParamsExpression)
        assertNull(config.body)
        assertNull(config.responseTimeoutMs)
        assertNull(config.followRedirects)
        assertNull(config.sendBodyMode)
        assertNull(config.requestStreamingMode)
        assertNull(config.sendCorrelationId)
        assertNull(config.correlationId)
        assertNull(config.targetConfig)
        assertTrue(config.responseValidator is DefaultResponseValidator)
    }

    @Test
    fun `should create config with all custom values`() {
        val connectionConfig = createConnectionConfig()
        val targetConfig = TargetConfig.of("payload")
        val customValidator = SuccessStatusCodeValidator(200..201)

        val config = HttpRequestConfig(
            docName = "Test Request",
            docId = "custom-id",
            docDescription = "Test description",
            sourceFile = "TestFile.kt",
            sourceLine = 42,
            sourceClass = "TestClass",
            connectionConfig = connectionConfig,
            path = "/api/custom",
            url = "https://api.example.test/resources/{id}",
            method = HttpMethod.POST,
            headers = mapOf("Content-Type" to "application/json"),
            headersExpression = "#[vars.headers]",
            queryParams = mapOf("key" to "value"),
            queryParamsExpression = "#[vars.query]",
            uriParams = mapOf("id" to "42"),
            uriParamsExpression = "#[vars.uri]",
            body = "{\"test\": true}",
            responseTimeoutMs = 5000,
            followRedirects = false,
            sendBodyMode = HttpSendBodyMode.ALWAYS,
            requestStreamingMode = HttpStreamingMode.NEVER,
            sendCorrelationId = SendCorrelationId.ALWAYS,
            correlationId = "#[vars.correlationId]",
            targetConfig = targetConfig,
            responseValidator = customValidator
        )

        assertEquals("Test Request", config.docName)
        assertEquals("custom-id", config.docId)
        assertEquals("Test description", config.docDescription)
        assertEquals("TestFile.kt", config.sourceFile)
        assertEquals(42, config.sourceLine)
        assertEquals("TestClass", config.sourceClass)
        assertEquals(connectionConfig, config.connectionConfig)
        assertEquals("/api/custom", config.path)
        assertEquals("https://api.example.test/resources/{id}", config.url)
        assertEquals(HttpMethod.POST, config.method)
        assertEquals(mapOf("Content-Type" to "application/json"), config.headers)
        assertEquals("#[vars.headers]", config.headersExpression)
        assertEquals(mapOf("key" to "value"), config.queryParams)
        assertEquals("#[vars.query]", config.queryParamsExpression)
        assertEquals(mapOf("id" to "42"), config.uriParams)
        assertEquals("#[vars.uri]", config.uriParamsExpression)
        assertEquals("{\"test\": true}", config.body)
        assertEquals(5000, config.responseTimeoutMs)
        assertEquals(false, config.followRedirects)
        assertEquals(HttpSendBodyMode.ALWAYS, config.sendBodyMode)
        assertEquals(HttpStreamingMode.NEVER, config.requestStreamingMode)
        assertEquals(SendCorrelationId.ALWAYS, config.sendCorrelationId)
        assertEquals("#[vars.correlationId]", config.correlationId)
        assertEquals(targetConfig, config.targetConfig)
        assertEquals(customValidator, config.responseValidator)
    }

    @Test
    fun `should use different HTTP methods`() {
        val connectionConfig = createConnectionConfig()

        val getConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/get",
            method = HttpMethod.GET
        )
        assertEquals(HttpMethod.GET, getConfig.method)

        val postConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/post",
            method = HttpMethod.POST
        )
        assertEquals(HttpMethod.POST, postConfig.method)

        val putConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/put",
            method = HttpMethod.PUT
        )
        assertEquals(HttpMethod.PUT, putConfig.method)

        val deleteConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/delete",
            method = HttpMethod.DELETE
        )
        assertEquals(HttpMethod.DELETE, deleteConfig.method)
    }

    @Test
    fun `should support all SendCorrelationId values`() {
        val connectionConfig = createConnectionConfig()

        val autoConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            sendCorrelationId = SendCorrelationId.AUTO
        )
        assertEquals(SendCorrelationId.AUTO, autoConfig.sendCorrelationId)

        val alwaysConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            sendCorrelationId = SendCorrelationId.ALWAYS
        )
        assertEquals(SendCorrelationId.ALWAYS, alwaysConfig.sendCorrelationId)

        val neverConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            sendCorrelationId = SendCorrelationId.NEVER
        )
        assertEquals(SendCorrelationId.NEVER, neverConfig.sendCorrelationId)
    }

    @Test
    fun `should support different response validators`() {
        val connectionConfig = createConnectionConfig()

        val defaultConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            responseValidator = DefaultResponseValidator()
        )
        assertTrue(defaultConfig.responseValidator is DefaultResponseValidator)

        val successValidator = SuccessStatusCodeValidator(200..299)
        val successConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            responseValidator = successValidator
        )
        assertEquals(successValidator, successConfig.responseValidator)

        val failureValidator = FailureStatusCodeValidator(400..599)
        val failureConfig = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            responseValidator = failureValidator
        )
        assertEquals(failureValidator, failureConfig.responseValidator)
    }

    @Test
    fun `should generate unique docId by default`() {
        val connectionConfig = createConnectionConfig()

        val config1 = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test"
        )
        val config2 = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test"
        )

        assertNotNull(config1.docId)
        assertNotNull(config2.docId)
        assertTrue(config1.docId != config2.docId)

        assertDoesNotThrow { UUID.fromString(config1.docId) }
        assertDoesNotThrow { UUID.fromString(config2.docId) }
    }

    @Test
    fun `should allow null body`() {
        val connectionConfig = createConnectionConfig()

        val config = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            body = null
        )

        assertNull(config.body)
    }

    @Test
    fun `should allow null responseTimeoutMs`() {
        val connectionConfig = createConnectionConfig()

        val config = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            responseTimeoutMs = null
        )

        assertNull(config.responseTimeoutMs)
    }

    @Test
    fun `should allow null followRedirects`() {
        val connectionConfig = createConnectionConfig()

        val config = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            followRedirects = null
        )

        assertNull(config.followRedirects)
    }

    @Test
    fun `should allow null targetConfig`() {
        val connectionConfig = createConnectionConfig()

        val config = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            targetConfig = null
        )

        assertNull(config.targetConfig)
    }

    @Test
    fun `should support empty headers and queryParams`() {
        val connectionConfig = createConnectionConfig()

        val config = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            headers = emptyMap(),
            queryParams = emptyMap()
        )

        assertEquals(emptyMap(), config.headers)
        assertEquals(emptyMap(), config.queryParams)
    }

    @Test
    fun `should support multiple headers and queryParams`() {
        val connectionConfig = createConnectionConfig()

        val config = HttpRequestConfig(
            connectionConfig = connectionConfig,
            path = "/api/test",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer token123",
                "X-Custom-Header" to "custom-value"
            ),
            queryParams = mapOf(
                "page" to "1",
                "limit" to "10",
                "sort" to "desc"
            )
        )

        assertEquals(3, config.headers.size)
        assertEquals("application/json", config.headers["Content-Type"])
        assertEquals("Bearer token123", config.headers["Authorization"])
        assertEquals("custom-value", config.headers["X-Custom-Header"])

        assertEquals(3, config.queryParams.size)
        assertEquals("1", config.queryParams["page"])
        assertEquals("10", config.queryParams["limit"])
        assertEquals("desc", config.queryParams["sort"])
    }

    @Test
    fun `should reject a negative response timeout`() {
        assertThrows<IllegalArgumentException> {
            HttpRequestConfig(
                connectionConfig = createConnectionConfig(),
                responseTimeoutMs = -1,
            )
        }
    }

    private fun assertDoesNotThrow(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            throw AssertionError("Expected no exception but got: ${e.message}", e)
        }
    }
}

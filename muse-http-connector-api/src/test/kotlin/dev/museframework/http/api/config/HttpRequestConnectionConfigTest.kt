package dev.museframework.http.api.config

import dev.museframework.http.api.model.HttpProtocol
import org.junit.jupiter.api.assertThrows
import dev.museframework.dsl.api.model.config.MuseConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HttpRequestConnectionConfigTest {

    @Test
    fun `should create config with default values`() {
        val config = HttpRequestConnectionConfig(
            host = "api.example.com"
        )

        assertEquals("", config.name)
        assertEquals("api.example.com", config.host)
        assertEquals(80, config.port)
        assertEquals(HttpProtocol.HTTP, config.protocol)
        assertEquals("/", config.basePath)
        assertEquals(30000, config.connectionTimeoutMs)
        assertEquals(30000, config.connectionIdleTimeoutMs)
        assertEquals(30000, config.responseTimeoutMs)
        assertTrue(config.followRedirects)
        assertTrue(config.usePersistentConnections)
        assertEquals(-1, config.maxConnections)
        assertTrue(config.enableCookies)
        assertTrue(config.defaultHeaders.isEmpty())
        assertTrue(config.defaultQueryParams.isEmpty())
        assertEquals(SendCorrelationId.AUTO, config.sendCorrelationId)
        assertEquals(HttpSendBodyMode.AUTO, config.sendBodyMode)
        assertEquals(HttpStreamingMode.AUTO, config.requestStreamingMode)
        assertEquals("", config.docName)
        assertNotNull(config.docId)
        assertNull(config.docDescription)
        assertNull(config.sourceFile)
        assertNull(config.sourceLine)
        assertNull(config.sourceClass)
    }

    @Test
    fun `should create config with custom values`() {
        val config = HttpRequestConnectionConfig(
            name = "secure-api",
            docName = "Test Config",
            docId = "custom-id",
            docDescription = "Test description",
            sourceFile = "TestFile.kt",
            sourceLine = 42,
            sourceClass = "TestClass",
            host = "secure.api.com",
            port = 443,
            protocol = HttpProtocol.HTTPS,
            basePath = "/api/v1",
            connectionTimeoutMs = 15000,
            connectionIdleTimeoutMs = 60000,
            responseTimeoutMs = 45000,
            followRedirects = false,
            usePersistentConnections = false,
            maxConnections = 100,
            enableCookies = false,
            defaultHeaders = mapOf("Accept" to "application/json"),
            defaultQueryParams = mapOf("locale" to "en-GB"),
            sendCorrelationId = SendCorrelationId.NEVER,
            sendBodyMode = HttpSendBodyMode.ALWAYS,
            requestStreamingMode = HttpStreamingMode.NEVER,
        )

        assertEquals("secure-api", config.name)
        assertEquals("Test Config", config.docName)
        assertEquals("custom-id", config.docId)
        assertEquals("Test description", config.docDescription)
        assertEquals("TestFile.kt", config.sourceFile)
        assertEquals(42, config.sourceLine)
        assertEquals("TestClass", config.sourceClass)
        assertEquals("secure.api.com", config.host)
        assertEquals(443, config.port)
        assertEquals(HttpProtocol.HTTPS, config.protocol)
        assertEquals("/api/v1", config.basePath)
        assertEquals(15000, config.connectionTimeoutMs)
        assertEquals(60000, config.connectionIdleTimeoutMs)
        assertEquals(45000, config.responseTimeoutMs)
        assertFalse(config.followRedirects)
        assertFalse(config.usePersistentConnections)
        assertEquals(100, config.maxConnections)
        assertFalse(config.enableCookies)
        assertEquals(mapOf("Accept" to "application/json"), config.defaultHeaders)
        assertEquals(mapOf("locale" to "en-GB"), config.defaultQueryParams)
        assertEquals(SendCorrelationId.NEVER, config.sendCorrelationId)
        assertEquals(HttpSendBodyMode.ALWAYS, config.sendBodyMode)
        assertEquals(HttpStreamingMode.NEVER, config.requestStreamingMode)
    }

    @Test
    fun `should select the default port from the protocol`() {
        assertEquals(80, HttpRequestConnectionConfig(protocol = HttpProtocol.HTTP).port)
        assertEquals(443, HttpRequestConnectionConfig(protocol = HttpProtocol.HTTPS).port)
    }

    @Test
    fun `should reject invalid connection limits`() {
        assertThrows<IllegalArgumentException> {
            HttpRequestConnectionConfig(port = 0)
        }
        assertThrows<IllegalArgumentException> {
            HttpRequestConnectionConfig(maxConnections = 0)
        }
        assertThrows<IllegalArgumentException> {
            HttpRequestConnectionConfig(connectionTimeoutMs = -1)
        }
    }

    @Test
    fun `should implement MuseConfiguration interface`() {
        val config = HttpRequestConnectionConfig(host = "test.com")
        val configuration: MuseConfiguration = config

        assertSame(config, configuration)
    }
}

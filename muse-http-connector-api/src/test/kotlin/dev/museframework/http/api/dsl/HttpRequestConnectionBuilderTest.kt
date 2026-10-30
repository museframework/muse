package dev.museframework.http.api.dsl

import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.http.api.config.HttpSendBodyMode
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.config.SendCorrelationId
import dev.museframework.dsl.api.model.config.ConfigRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpRequestConnectionBuilderTest {

    @Test
    fun `should create config with default values`() {
        val config = httpRequestConnection {
            name = "default-api"
            host = "api.example.com"
        }

        assertEquals("default-api", config.name)
        assertEquals("api.example.com", config.host)
        assertEquals(80, config.port)
        assertEquals(HttpProtocol.HTTP, config.protocol)
        assertEquals("/", config.basePath)
        assertEquals(30000, config.connectionTimeoutMs)
        assertEquals(30000, config.connectionIdleTimeoutMs)
        assertEquals(30000, config.responseTimeoutMs)
        assertEquals(true, config.followRedirects)
        assertEquals(true, config.usePersistentConnections)
        assertEquals(-1, config.maxConnections)
        assertEquals(true, config.enableCookies)
        assertEquals(SendCorrelationId.AUTO, config.sendCorrelationId)
        assertEquals(HttpSendBodyMode.AUTO, config.sendBodyMode)
        assertEquals(HttpStreamingMode.AUTO, config.requestStreamingMode)
        assertTrue(config.docId.isNotBlank())
        assertEquals("", config.docName)
        assertNotNull(config.sourceFile)
        assertNotNull(config.sourceLine)
        assertNotNull(config.sourceClass)
    }

    @Test
    fun `should create config with custom values`() {
        val config = httpRequestConnection {
            docId = "custom-id"
            name = "custom-api"
            docName = "My Connection"
            host = "api.example.com"
            port = 443
            protocol = HttpProtocol.HTTPS
            basePath = "/v1"
            connectionTimeoutMs = 15000
            connectionIdleTimeoutMs = 60000
            responseTimeoutMs = 45000
            followRedirects = false
            usePersistentConnections = false
            maxConnections = 10
            enableCookies = false
            sendCorrelationId = SendCorrelationId.NEVER
            sendBodyMode = HttpSendBodyMode.ALWAYS
            requestStreamingMode = HttpStreamingMode.NEVER
            defaultHeader("Accept", "application/json")
            defaultQueryParam("locale", "en-GB")
        }

        assertEquals("custom-id", config.docId)
        assertEquals("custom-api", config.name)
        assertEquals("My Connection", config.docName)
        assertEquals("api.example.com", config.host)
        assertEquals(443, config.port)
        assertEquals(HttpProtocol.HTTPS, config.protocol)
        assertEquals("/v1", config.basePath)
        assertEquals(15000, config.connectionTimeoutMs)
        assertEquals(60000, config.connectionIdleTimeoutMs)
        assertEquals(45000, config.responseTimeoutMs)
        assertEquals(false, config.followRedirects)
        assertEquals(false, config.usePersistentConnections)
        assertEquals(10, config.maxConnections)
        assertEquals(false, config.enableCookies)
        assertEquals(SendCorrelationId.NEVER, config.sendCorrelationId)
        assertEquals(HttpSendBodyMode.ALWAYS, config.sendBodyMode)
        assertEquals(HttpStreamingMode.NEVER, config.requestStreamingMode)
        assertEquals(mapOf("Accept" to "application/json"), config.defaultHeaders)
        assertEquals(mapOf("locale" to "en-GB"), config.defaultQueryParams)
    }

    @Test
    fun `should select HTTPS default port when no port is configured`() {
        val config = httpRequestConnection {
            protocol = HttpProtocol.HTTPS
            host = "secure.example.com"
        }

        assertEquals(443, config.port)
    }

    @Test
    fun `should use explicit source location when provided`() {
        val config = httpRequestConnection {
            host = "api.example.com"
            sourceFile = "CustomFile.kt"
            sourceLine = 42
            sourceClass = "CustomClass"
        }

        assertEquals("CustomFile.kt", config.sourceFile)
        assertEquals(42, config.sourceLine)
        assertEquals("CustomClass", config.sourceClass)
    }

    @Test
    fun `should generate unique docId by default`() {
        val config1 = httpRequestConnection { host = "api1.example.com" }
        val config2 = httpRequestConnection { host = "api2.example.com" }

        assertNotEquals(config1.docId, config2.docId)
    }

    @Test
    fun `should create a named connection reference`() {
        assertEquals(ConfigRef.ByName("remote-api"), httpRequestConnection("remote-api"))
    }

    @Test
    fun `should create a typed connection reference`() {
        val config = httpRequestConnection { host = "api.example.com" }

        assertEquals(ConfigRef.ByReference(config), httpRequestConnection(config))
    }
}

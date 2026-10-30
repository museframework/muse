package dev.museframework.http.api.model

import dev.museframework.http.api.config.HttpStreamingMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HttpModelsTest {

    @Test
    fun `HttpRequest should be immutable and preserve all properties`() {
        val headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        val queryParams = mapOf("id" to "123", "filter" to "active")
        val body = "request body".toByteArray()

        val request = HttpRequest(
            method = HttpMethod.POST,
            requestPath = "/api/users",
            queryParams = queryParams,
            headers = headers,
            body = body,
            contentType = "application/json",
            remoteAddress = "192.168.1.1",
            rawRequestPath = "/api/users",
            requestUri = "/api/users?id=123",
            rawRequestUri = "/api/users?id=123",
            queryString = "id=123",
            uriParams = mapOf("resource" to "users"),
            version = "HTTP/1.1",
            scheme = "https",
            localAddress = "127.0.0.1:8443",
        )

        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/api/users", request.requestPath)
        assertEquals(queryParams, request.queryParams)
        assertEquals(headers, request.headers)
        assertArrayEquals(body, request.body)
        assertEquals("application/json", request.contentType)
        assertEquals("192.168.1.1", request.remoteAddress)
        assertEquals("/api/users", request.rawRequestPath)
        assertEquals("/api/users?id=123", request.requestUri)
        assertEquals("/api/users?id=123", request.rawRequestUri)
        assertEquals("id=123", request.queryString)
        assertEquals(mapOf("resource" to "users"), request.uriParams)
        assertEquals("HTTP/1.1", request.version)
        assertEquals("https", request.scheme)
        assertEquals("127.0.0.1:8443", request.localAddress)
        assertNull(request.clientCertificate)
    }

    @Test
    fun `HttpRequest should support defaults for optional fields`() {
        val request = HttpRequest(
            method = HttpMethod.GET,
            requestPath = "/health",
        )

        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/health", request.requestPath)
        assertTrue(request.queryParams.isEmpty())
        assertTrue(request.headers.isEmpty())
        assertNull(request.body)
        assertNull(request.contentType)
        assertEquals("", request.remoteAddress)
        assertEquals("/health", request.rawRequestPath)
        assertEquals("/health", request.requestUri)
        assertEquals("/health", request.rawRequestUri)
        assertEquals("", request.queryString)
        assertTrue(request.uriParams.isEmpty())
        assertEquals("", request.version)
        assertEquals("", request.scheme)
        assertEquals("", request.localAddress)
        assertNull(request.clientCertificate)
    }

    @Test
    fun `HttpRequest should handle null body correctly`() {
        val request = HttpRequest(
            method = HttpMethod.DELETE,
            requestPath = "/api/users/123",
            body = null,
        )

        assertNull(request.body)
    }

    @Test
    fun `HttpRequest should handle empty collections`() {
        val request = HttpRequest(
            method = HttpMethod.GET,
            requestPath = "/api/users",
            queryParams = emptyMap(),
            headers = emptyMap(),
        )

        assertTrue(request.queryParams.isEmpty())
        assertTrue(request.headers.isEmpty())
    }

    @Test
    fun `HttpRequest copy should create independent instance`() {
        val original = HttpRequest(
            method = HttpMethod.POST,
            requestPath = "/api/users",
            headers = mapOf("Content-Type" to "application/json"),
        )

        val copied = original.copy(method = HttpMethod.PUT)

        assertEquals(HttpMethod.PUT, copied.method)
        assertEquals(HttpMethod.POST, original.method)
        assertEquals("/api/users", copied.requestPath)
    }

    @Test
    fun `HttpResponse should be immutable and preserve all properties`() {
        val headers = mapOf("Content-Type" to "application/json", "X-Request-ID" to "abc-123")
        val body = """{"status":"success"}""".toByteArray()

        val response = HttpResponse(
            statusCode = 201,
            headers = headers,
            body = body,
            reasonPhrase = "Created",
            streamingMode = HttpStreamingMode.ALWAYS,
        )

        assertEquals(201, response.statusCode)
        assertEquals(headers, response.headers)
        assertArrayEquals(body, response.body)
        assertEquals("Created", response.reasonPhrase)
        assertEquals(HttpStreamingMode.ALWAYS, response.streamingMode)
    }

    @Test
    fun `HttpResponse should support defaults for optional fields`() {
        val response = HttpResponse(statusCode = 200)

        assertEquals(200, response.statusCode)
        assertTrue(response.headers.isEmpty())
        assertNull(response.body)
        assertNull(response.reasonPhrase)
        assertEquals(HttpStreamingMode.AUTO, response.streamingMode)
    }

    @Test
    fun `HttpResponse should handle common HTTP status codes`() {
        val ok = HttpResponse(statusCode = 200)
        val created = HttpResponse(statusCode = 201)
        val noContent = HttpResponse(statusCode = 204)
        val badRequest = HttpResponse(statusCode = 400)
        val unauthorized = HttpResponse(statusCode = 401)
        val notFound = HttpResponse(statusCode = 404)
        val serverError = HttpResponse(statusCode = 500)
        val gatewayTimeout = HttpResponse(statusCode = 504)

        assertEquals(200, ok.statusCode)
        assertEquals(201, created.statusCode)
        assertEquals(204, noContent.statusCode)
        assertEquals(400, badRequest.statusCode)
        assertEquals(401, unauthorized.statusCode)
        assertEquals(404, notFound.statusCode)
        assertEquals(500, serverError.statusCode)
        assertEquals(504, gatewayTimeout.statusCode)
    }

    @Test
    fun `HttpResponse should handle null body correctly`() {
        val response = HttpResponse(
            statusCode = 204,
            body = null,
        )

        assertEquals(204, response.statusCode)
        assertNull(response.body)
    }

    @Test
    fun `HttpResponse should handle empty body`() {
        val response = HttpResponse(
            statusCode = 200,
            body = ByteArray(0),
        )

        assertEquals(200, response.statusCode)
        assertNotNull(response.body)
        assertEquals(0, response.body!!.size)
    }

    @Test
    fun `HttpResponse copy should create independent instance`() {
        val original = HttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
        )

        val copied = original.copy(statusCode = 201)

        assertEquals(201, copied.statusCode)
        assertEquals(200, original.statusCode)
        assertEquals(original.headers, copied.headers)
    }

    @Test
    fun `HttpMethod enum should contain all standard HTTP methods`() {
        val methods = HttpMethod.values()

        assertTrue(methods.contains(HttpMethod.GET))
        assertTrue(methods.contains(HttpMethod.POST))
        assertTrue(methods.contains(HttpMethod.PUT))
        assertTrue(methods.contains(HttpMethod.DELETE))
        assertTrue(methods.contains(HttpMethod.PATCH))
        assertTrue(methods.contains(HttpMethod.HEAD))
        assertTrue(methods.contains(HttpMethod.OPTIONS))
        assertTrue(methods.contains(HttpMethod.TRACE))
        assertTrue(methods.contains(HttpMethod.ANY))
    }

    @Test
    fun `HttpMethod ANY should be distinct from other methods`() {
        assertNotEquals(HttpMethod.GET, HttpMethod.ANY)
        assertNotEquals(HttpMethod.POST, HttpMethod.ANY)
        assertEquals(HttpMethod.ANY, HttpMethod.ANY)
    }

    @Test
    fun `HttpRequest should preserve byte array integrity`() {
        val originalBytes = ByteArray(256) { it.toByte() }
        val request = HttpRequest(
            method = HttpMethod.POST,
            requestPath = "/api/upload",
            body = originalBytes,
        )

        assertNotNull(request.body)
        assertArrayEquals(originalBytes, request.body)
        assertEquals(256, request.body!!.size)
    }

    @Test
    fun `HttpResponse should preserve byte array integrity`() {
        val originalBytes = ByteArray(256) { it.toByte() }
        val response = HttpResponse(
            statusCode = 200,
            body = originalBytes,
        )

        assertNotNull(response.body)
        assertArrayEquals(originalBytes, response.body)
        assertEquals(256, response.body!!.size)
    }
}

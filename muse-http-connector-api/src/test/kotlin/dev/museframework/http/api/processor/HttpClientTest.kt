package dev.museframework.http.api.processor

import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.config.HttpStreamingMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HttpClientTest {

    @Test
    fun `HttpOutboundRequest should be immutable and preserve all properties`() {
        val headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        val queryParams = mapOf("id" to "123", "filter" to "active")
        val body = "request body".toByteArray()

        val request = HttpOutboundRequest(
            method = HttpMethod.POST,
            url = "https://api.example.com/users",
            headers = headers,
            queryParams = queryParams,
            body = body,
            contentType = "application/json",
            responseTimeoutMs = 5000L,
            followRedirects = false,
            requestStreamingMode = HttpStreamingMode.ALWAYS,
        )

        assertEquals(HttpMethod.POST, request.method)
        assertEquals("https://api.example.com/users", request.url)
        assertEquals(headers, request.headers)
        assertEquals(queryParams, request.queryParams)
        assertArrayEquals(body, request.body)
        assertEquals("application/json", request.contentType)
        assertEquals(5000L, request.responseTimeoutMs)
        assertFalse(request.followRedirects)
        assertEquals(HttpStreamingMode.ALWAYS, request.requestStreamingMode)
    }

    @Test
    fun `HttpOutboundRequest should support defaults for optional fields`() {
        val request = HttpOutboundRequest(
            method = HttpMethod.GET,
            url = "https://api.example.com/health",
        )

        assertEquals(HttpMethod.GET, request.method)
        assertEquals("https://api.example.com/health", request.url)
        assertTrue(request.headers.isEmpty())
        assertTrue(request.queryParams.isEmpty())
        assertNull(request.body)
        assertNull(request.contentType)
        assertNull(request.responseTimeoutMs)
        assertTrue(request.followRedirects)
        assertEquals(HttpStreamingMode.AUTO, request.requestStreamingMode)
    }

    @Test
    fun `HttpOutboundRequest should handle null body correctly`() {
        val request = HttpOutboundRequest(
            method = HttpMethod.DELETE,
            url = "https://api.example.com/users/123",
            body = null,
        )

        assertNull(request.body)
    }

    @Test
    fun `HttpOutboundRequest should handle empty collections`() {
        val request = HttpOutboundRequest(
            method = HttpMethod.GET,
            url = "https://api.example.com/users",
            headers = emptyMap(),
            queryParams = emptyMap(),
        )

        assertTrue(request.headers.isEmpty())
        assertTrue(request.queryParams.isEmpty())
    }

    @Test
    fun `HttpOutboundRequest copy should create independent instance`() {
        val original = HttpOutboundRequest(
            method = HttpMethod.POST,
            url = "https://api.example.com/users",
            headers = mapOf("Content-Type" to "application/json"),
        )

        val copied = original.copy(method = HttpMethod.PUT)

        assertEquals(HttpMethod.PUT, copied.method)
        assertEquals(HttpMethod.POST, original.method)
        assertEquals(original.url, copied.url)
    }

    @Test
    fun `HttpOutboundRequest should support all HTTP methods`() {
        val get = HttpOutboundRequest(method = HttpMethod.GET, url = "https://api.example.com/get")
        val post = HttpOutboundRequest(method = HttpMethod.POST, url = "https://api.example.com/post")
        val put = HttpOutboundRequest(method = HttpMethod.PUT, url = "https://api.example.com/put")
        val delete = HttpOutboundRequest(method = HttpMethod.DELETE, url = "https://api.example.com/delete")
        val patch = HttpOutboundRequest(method = HttpMethod.PATCH, url = "https://api.example.com/patch")
        val head = HttpOutboundRequest(method = HttpMethod.HEAD, url = "https://api.example.com/head")
        val options = HttpOutboundRequest(method = HttpMethod.OPTIONS, url = "https://api.example.com/options")

        assertEquals(HttpMethod.GET, get.method)
        assertEquals(HttpMethod.POST, post.method)
        assertEquals(HttpMethod.PUT, put.method)
        assertEquals(HttpMethod.DELETE, delete.method)
        assertEquals(HttpMethod.PATCH, patch.method)
        assertEquals(HttpMethod.HEAD, head.method)
        assertEquals(HttpMethod.OPTIONS, options.method)
    }

    @Test
    fun `HttpOutboundRequest should preserve byte array integrity`() {
        val originalBytes = ByteArray(256) { it.toByte() }
        val request = HttpOutboundRequest(
            method = HttpMethod.POST,
            url = "https://api.example.com/upload",
            body = originalBytes,
        )

        assertNotNull(request.body)
        assertArrayEquals(originalBytes, request.body)
        assertEquals(256, request.body!!.size)
    }

    @Test
    fun `HttpOutboundRequest should handle various timeout values`() {
        val shortTimeout = HttpOutboundRequest(
            method = HttpMethod.GET,
            url = "https://api.example.com/fast",
            responseTimeoutMs = 1000L,
        )
        val longTimeout = HttpOutboundRequest(
            method = HttpMethod.GET,
            url = "https://api.example.com/slow",
            responseTimeoutMs = 60000L,
        )
        val noTimeout = HttpOutboundRequest(
            method = HttpMethod.GET,
            url = "https://api.example.com/default",
        )

        assertEquals(1000L, shortTimeout.responseTimeoutMs)
        assertEquals(60000L, longTimeout.responseTimeoutMs)
        assertNull(noTimeout.responseTimeoutMs)
    }

    @Test
    fun `HttpOutboundRequest should handle redirect settings`() {
        val follow = HttpOutboundRequest(
            method = HttpMethod.GET,
            url = "https://api.example.com/redirect",
            followRedirects = true,
        )
        val noFollow = HttpOutboundRequest(
            method = HttpMethod.GET,
            url = "https://api.example.com/no-redirect",
            followRedirects = false,
        )
        val default = HttpOutboundRequest(
            method = HttpMethod.GET,
            url = "https://api.example.com/default",
        )

        assertTrue(follow.followRedirects)
        assertFalse(noFollow.followRedirects)
        assertTrue(default.followRedirects)
    }

    @Test
    fun `HttpClient interface should have execute and close methods`() {
        val methods = HttpClient::class.java.methods

        val hasExecute = methods.any { it.name == "execute" }
        val hasClose = methods.any { it.name == "close" }

        assertTrue(hasExecute, "HttpClient should have execute method")
        assertTrue(hasClose, "HttpClient should have close method")
    }
}

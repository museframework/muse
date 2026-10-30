package dev.museframework.http.testkit

import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpRequest
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.server.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * Reusable behavioral contract for [HttpServer] adapters.
 *
 * A concrete adapter supplies a started endpoint and its server SPI instance.
 * The contract drives that endpoint with the JDK client to avoid framework
 * coupling in the published testkit.
 */
abstract class HttpServerContract {
    private val client = HttpClient.newHttpClient()
    private lateinit var server: HttpServer

    protected abstract fun createServer(): HttpServer

    protected abstract fun baseUri(): URI

    @BeforeEach
    fun startContractServer() {
        server = createServer()
        runBlocking { server.start() }
    }

    @AfterEach
    fun stopContractServer() {
        if (::server.isInitialized) {
            runBlocking { server.stop() }
        }
    }

    @Test
    fun `server maps request metadata and response metadata`() {
        val recorded = AtomicReference<HttpRequest?>()
        server.register("/contract/metadata/{id}", listOf(HttpMethod.GET)) { request ->
            recorded.set(request)
            HttpResponse(
                statusCode = 201,
                headers = mapOf("X-Contract" to "server-response"),
                body = "created".toByteArray(),
            )
        }

        val response =
            execute(
                method = "GET",
                path = "/contract/metadata/line%20item+plus?expand=line%20items+plus",
                headers = mapOf("X-Request-Contract" to "server-request"),
            )

        val request = checkNotNull(recorded.get())
        assertEquals(201, response.statusCode())
        assertEquals("server-response", response.headers().firstValue("X-Contract").orElse(null))
        assertArrayEquals("created".toByteArray(), response.body())
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/contract/metadata/line item+plus", request.requestPath)
        assertEquals("/contract/metadata/line%20item+plus", request.rawRequestPath)
        assertEquals("/contract/metadata/line item+plus?expand=line items+plus", request.requestUri)
        assertEquals("/contract/metadata/line%20item+plus?expand=line%20items+plus", request.rawRequestUri)
        assertEquals("expand=line items+plus", request.queryString)
        assertEquals("line items plus", request.queryParams["expand"])
        assertEquals("line item+plus", request.uriParams["id"])
        assertEquals("http", request.scheme)
        assertTrue(request.version.startsWith("HTTP/"))
        assertTrue(request.localAddress.isNotBlank())
        assertTrue(request.remoteAddress.isNotBlank())
        assertEquals("server-request", request.headers.value("X-Request-Contract"))
    }

    @Test
    fun `server passes request body and content type to the handler`() {
        val recorded = AtomicReference<HttpRequest?>()
        server.register("/contract/body", listOf(HttpMethod.POST)) { request ->
            recorded.set(request)
            val body =
                request.bodyProvider?.openCursor()?.use { it.readBytes() }
                    ?: request.body
            HttpResponse(statusCode = 202, body = body)
        }

        val response =
            execute(
                method = "POST",
                path = "/contract/body",
                headers = mapOf("Content-Type" to "text/plain"),
                body = "payload".toByteArray(),
            )

        val request = checkNotNull(recorded.get())
        assertEquals(202, response.statusCode())
        assertEquals("text/plain", request.contentType?.substringBefore(";"))
        assertNotNull(request.bodyProvider)
        assertNull(request.body, "Provider-aware adapters must not duplicate the request body by default")
        assertArrayEquals("payload".toByteArray(), response.body())
    }

    @Test
    fun `server forces chunked transfer for always streaming byte responses`() {
        server.register("/contract/stream-always", listOf(HttpMethod.GET)) {
            HttpResponse(
                statusCode = 200,
                body = "streamed".toByteArray(),
                streamingMode = HttpStreamingMode.ALWAYS,
            )
        }

        val response = execute("GET", "/contract/stream-always")

        assertArrayEquals("streamed".toByteArray(), response.body())
        assertEquals("chunked", response.headers().firstValue("Transfer-Encoding").orElse(null))
    }

    @Test
    fun `server materializes never streaming provider responses`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("materialized".toByteArray())
        server.register("/contract/stream-never", listOf(HttpMethod.GET)) {
            HttpResponse(
                statusCode = 200,
                bodyProvider = provider,
                streamingMode = HttpStreamingMode.NEVER,
            )
        }

        val response = execute("GET", "/contract/stream-never")

        assertArrayEquals("materialized".toByteArray(), response.body())
        assertEquals("12", response.headers().firstValue("Content-Length").orElse(null))
        assertTrue(provider.isClosed)
    }

    @Test
    fun `server filters methods and removes unregistered handlers`() {
        val path = "/contract/lifecycle"
        val methods = listOf(HttpMethod.GET)
        server.register(path, methods) { HttpResponse(statusCode = 200, body = "active".toByteArray()) }

        assertEquals(404, execute("POST", path).statusCode())
        assertEquals(200, execute("GET", path).statusCode())

        server.unregister(path, methods)

        assertEquals(404, execute("GET", path).statusCode())
    }

    @Test
    fun `server returns an empty body when the handler omits it`() {
        server.register("/contract/no-content", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 204)
        }

        val response = execute("GET", "/contract/no-content")

        assertEquals(204, response.statusCode())
        assertEquals(0, response.body().size)
    }

    @Test
    fun `server extracts named parameters adjacent to a double wildcard`() {
        val recorded = AtomicReference<HttpRequest?>()
        server.register("/contract/nested/{id}/**", listOf(HttpMethod.GET)) { request ->
            recorded.set(request)
            HttpResponse(statusCode = 200)
        }
        assertEquals(200, execute("GET", "/contract/nested/A%20B+plus/one/two").statusCode())
        assertEquals("A B+plus", checkNotNull(recorded.get()).uriParams["id"])
    }

    private fun execute(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): JdkHttpResponse<ByteArray> {
        val request =
            JdkHttpRequest
                .newBuilder(baseUri().resolve(path))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .method(
                    method,
                    body?.let { JdkHttpRequest.BodyPublishers.ofByteArray(it) }
                        ?: JdkHttpRequest.BodyPublishers.noBody(),
                ).build()

        return client.send(request, JdkHttpResponse.BodyHandlers.ofByteArray())
    }

    private fun Map<String, String>.value(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}

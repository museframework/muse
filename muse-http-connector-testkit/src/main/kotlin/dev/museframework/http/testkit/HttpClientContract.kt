package dev.museframework.http.testkit

import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.api.processor.HttpOutboundRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer as JdkHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Reusable behavioral contract for [HttpClient] adapters.
 *
 * Concrete adapters only provide a client factory. The contract owns its
 * loopback origin so every adapter observes the same requests and responses.
 */
abstract class HttpClientContract {
    private lateinit var origin: ContractOrigin
    private lateinit var client: HttpClient

    protected abstract fun createClient(config: HttpRequestConnectionConfig): HttpClient

    @BeforeEach
    fun startContractOrigin() {
        origin = ContractOrigin()
        client =
            createClient(connectionConfig())
    }

    @AfterEach
    fun stopContractOrigin() {
        if (::client.isInitialized) {
            client.close()
        }
        if (::origin.isInitialized) {
            origin.close()
        }
    }

    @Test
    fun `client returns response status headers and body`() =
        runBlocking {
            origin.respond(
                path = "/contract/response",
                response =
                    ContractResponse(
                        statusCode = 201,
                        headers = mapOf("X-Contract" to "client-response"),
                        body = "created".toByteArray(),
                    ),
            )

            val response =
                client.execute(
                    HttpOutboundRequest(
                        method = HttpMethod.GET,
                        url = origin.url("/contract/response"),
                    ),
                )

            assertEquals(201, response.statusCode)
            assertEquals("Created", response.reasonPhrase)
            assertEquals("client-response", response.headers.value("X-Contract"))
            assertArrayEquals("created".toByteArray(), response.bodyBytes())
        }

    @Test
    fun `client maps method query headers content type and body`() =
        runBlocking {
            val recorded = origin.capture("/contract/request")

            val response =
                client.execute(
                    HttpOutboundRequest(
                        method = HttpMethod.POST,
                        url = origin.url("/contract/request"),
                        headers = mapOf("Authorization" to "Bearer contract"),
                        queryParams = mapOf("expand" to "line items"),
                        body = "payload".toByteArray(),
                        contentType = "text/plain",
                    ),
                )

            val request = checkNotNull(recorded.get())
            assertEquals(202, response.statusCode)
            assertEquals("POST", request.method)
            assertEquals("/contract/request", request.path)
            assertEquals(mapOf("expand" to "line items"), request.queryParams)
            assertEquals("Bearer contract", request.headers.value("Authorization"))
            assertEquals("text/plain", request.headers.value("Content-Type"))
            assertArrayEquals("payload".toByteArray(), request.body)
        }

    @Test
    fun `client exposes non-success responses without throwing`() =
        runBlocking {
            origin.respond(
                path = "/contract/not-found",
                response = ContractResponse(statusCode = 404, body = "missing".toByteArray()),
            )

            val response =
                client.execute(
                    HttpOutboundRequest(
                        method = HttpMethod.GET,
                        url = origin.url("/contract/not-found"),
                    ),
                )

            assertEquals(404, response.statusCode)
            assertArrayEquals("missing".toByteArray(), response.bodyBytes())
        }

    @Test
    fun `client honors the per-request redirect policy`() =
        runBlocking {
            origin.respond(
                path = "/contract/redirect",
                response = ContractResponse(statusCode = 302, headers = mapOf("Location" to "/contract/target")),
            )
            origin.respond(
                path = "/contract/target",
                response = ContractResponse(statusCode = 200, body = "redirected".toByteArray()),
            )

            val followed =
                client.execute(
                    HttpOutboundRequest(
                        method = HttpMethod.GET,
                        url = origin.url("/contract/redirect"),
                        followRedirects = true,
                    ),
                )
            val retained =
                client.execute(
                    HttpOutboundRequest(
                        method = HttpMethod.GET,
                        url = origin.url("/contract/redirect"),
                        followRedirects = false,
                    ),
                )

            assertEquals(200, followed.statusCode)
            assertArrayEquals("redirected".toByteArray(), followed.bodyBytes())
            assertEquals(302, retained.statusCode)
            assertEquals("/contract/target", retained.headers.value("Location"))
        }

    @Test
    fun `client persists response cookies when enabled`() =
        runBlocking {
            origin.respond(
                path = "/contract/cookies/set",
                response = ContractResponse(statusCode = 204, headers = mapOf("Set-Cookie" to "session=contract; Path=/")),
            )
            val recorded = origin.capture("/contract/cookies/read")

            client.execute(HttpOutboundRequest(HttpMethod.GET, origin.url("/contract/cookies/set")))
            client.execute(HttpOutboundRequest(HttpMethod.GET, origin.url("/contract/cookies/read")))

            assertEquals("session=contract", recorded.get()?.headers?.value("Cookie"))
        }

    @Test
    fun `client omits stored cookies when disabled`() =
        runBlocking {
            val noCookieClient = createClient(connectionConfig(enableCookies = false))
            origin.respond(
                path = "/contract/no-cookies/set",
                response = ContractResponse(statusCode = 204, headers = mapOf("Set-Cookie" to "session=ignored; Path=/")),
            )
            val recorded = origin.capture("/contract/no-cookies/read")

            try {
                noCookieClient.execute(HttpOutboundRequest(HttpMethod.GET, origin.url("/contract/no-cookies/set")))
                noCookieClient.execute(HttpOutboundRequest(HttpMethod.GET, origin.url("/contract/no-cookies/read")))
            } finally {
                noCookieClient.close()
            }

            assertNull(recorded.get()?.headers?.value("Cookie"))
        }

    @Test
    fun `client honors request streaming modes`() =
        runBlocking {
            val streamed = origin.capture("/contract/streaming/always")
            val buffered = origin.capture("/contract/streaming/never")
            val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("buffered".toByteArray())

            client.execute(
                HttpOutboundRequest(
                    method = HttpMethod.POST,
                    url = origin.url("/contract/streaming/always"),
                    body = "streamed".toByteArray(),
                    requestStreamingMode = HttpStreamingMode.ALWAYS,
                ),
            )
            client.execute(
                HttpOutboundRequest(
                    method = HttpMethod.POST,
                    url = origin.url("/contract/streaming/never"),
                    bodyProvider = provider,
                    requestStreamingMode = HttpStreamingMode.NEVER,
                ),
            )

            assertEquals("chunked", streamed.get()?.headers?.value("Transfer-Encoding"))
            assertArrayEquals("streamed".toByteArray(), streamed.get()?.body)
            assertNull(buffered.get()?.headers?.value("Transfer-Encoding"))
            assertArrayEquals("buffered".toByteArray(), buffered.get()?.body)
        }

    @Test
    fun `client requests connection close when persistence is disabled`() =
        runBlocking {
            val nonPersistentClient = createClient(connectionConfig(usePersistentConnections = false))
            val recorded = origin.capture("/contract/non-persistent")

            try {
                nonPersistentClient.execute(HttpOutboundRequest(HttpMethod.GET, origin.url("/contract/non-persistent")))
            } finally {
                nonPersistentClient.close()
            }

            assertEquals("close", recorded.get()?.headers?.value("Connection"))
        }

    private fun connectionConfig(
        enableCookies: Boolean = true,
        usePersistentConnections: Boolean = true,
    ): HttpRequestConnectionConfig =
        HttpRequestConnectionConfig(
            name = "http-client-contract",
            host = origin.host,
            port = origin.port,
            protocol = HttpProtocol.HTTP,
            connectionTimeoutMs = 1_000,
            connectionIdleTimeoutMs = 1_000,
            responseTimeoutMs = 2_000,
            enableCookies = enableCookies,
            usePersistentConnections = usePersistentConnections,
        )

    private inner class ContractOrigin : AutoCloseable {
        private val server = JdkHttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        val host: String = server.address.address.hostAddress
        val port: Int
            get() = server.address.port

        init {
            server.start()
        }

        fun url(path: String): String = "http://$host:$port$path"

        fun respond(
            path: String,
            response: ContractResponse,
        ) {
            server.createContext(path) { exchange -> exchange.respond(response) }
        }

        fun capture(path: String): AtomicReference<RecordedRequest?> {
            val recorded = AtomicReference<RecordedRequest?>()
            server.createContext(path) { exchange ->
                recorded.set(exchange.toRecordedRequest())
                exchange.respond(ContractResponse(statusCode = 202))
            }
            return recorded
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class ContractResponse(
        val statusCode: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray? = null,
    )

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val queryParams: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun HttpExchange.toRecordedRequest(): RecordedRequest =
        RecordedRequest(
            method = requestMethod,
            path = requestURI.path,
            queryParams = requestURI.rawQuery.toQueryParams(),
            headers = requestHeaders.mapValues { (_, values) -> values.firstOrNull().orEmpty() },
            body = requestBody.use { it.readAllBytes() },
        )

    private fun HttpExchange.respond(response: ContractResponse) {
        response.headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        val body = response.body
        sendResponseHeaders(response.statusCode, body?.size?.toLong() ?: -1L)
        body?.let { responseBody.use { output -> output.write(it) } } ?: responseBody.close()
        close()
    }

    private fun HttpResponse.bodyBytes(): ByteArray? =
        bodyProvider?.openCursor()?.use { it.readBytes() } ?: body

    private fun String?.toQueryParams(): Map<String, String> =
        this
            ?.takeIf { it.isNotEmpty() }
            ?.split("&")
            ?.associate { parameter ->
                URLDecoder.decode(parameter.substringBefore("="), StandardCharsets.UTF_8) to
                    URLDecoder.decode(parameter.substringAfter("=", ""), StandardCharsets.UTF_8)
            }.orEmpty()

    private fun Map<String, String>.value(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}

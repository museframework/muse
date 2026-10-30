package dev.museframework.connectors.http.quarkus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.processor.HttpOutboundRequest
import io.vertx.core.Future
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuarkusWebClientHttpClientBehaviorIT {
    private lateinit var vertx: Vertx
    private lateinit var origin: HttpServer
    private lateinit var originExecutor: ExecutorService
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var client: QuarkusWebClientHttpClient

    @BeforeAll
    fun createVertx() {
        vertx = Vertx.vertx()
    }

    @BeforeEach
    fun startOriginAndClient() {
        originExecutor = Executors.newCachedThreadPool()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        origin = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        origin.executor = originExecutor
        origin.start()
        client = QuarkusWebClientHttpClient(vertx, connectionConfig())
    }

    @AfterEach
    fun stopOriginAndClient() {
        if (::client.isInitialized) client.close()
        if (::origin.isInitialized) origin.stop(0)
        if (::scheduler.isInitialized) scheduler.shutdownNow()
        if (::originExecutor.isInitialized) originExecutor.shutdownNow()
    }

    @AfterAll
    fun closeVertx() {
        if (::vertx.isInitialized) vertx.close().await()
    }

    @Test
    fun `bodyless responses do not create stream providers`() =
        runBlocking {
            origin.createContext("/head") { exchange -> exchange.respond(200, "ignored".toByteArray()) }
            origin.createContext("/no-content") { exchange -> exchange.respond(204) }
            origin.createContext("/not-modified") { exchange -> exchange.respond(304) }
            origin.createContext("/zero") { exchange ->
                exchange.responseHeaders.set("Content-Length", "0")
                exchange.respond(200)
            }

            val responses =
                listOf(
                    client.execute(request(HttpMethod.HEAD, "/head")),
                    client.execute(request(HttpMethod.GET, "/no-content")),
                    client.execute(request(HttpMethod.GET, "/not-modified")),
                    client.execute(request(HttpMethod.GET, "/zero")),
                )

            responses.forEach { response ->
                assertNull(response.bodyProvider)
                assertNull(response.body)
            }
        }

    @Test
    fun `outbound provider sends exact bytes and known content length`() =
        runBlocking {
            val capturedBody = AtomicReference<ByteArray>()
            val capturedLength = AtomicReference<String>()
            origin.createContext("/provider") { exchange ->
                capturedLength.set(exchange.requestHeaders.getFirst("Content-Length"))
                capturedBody.set(exchange.requestBody.use { it.readAllBytes() })
                exchange.respond(202)
            }
            val payload = "provider-payload".toByteArray()
            val provider = RepeatableInMemoryCursorStreamProvider.fromBytes(payload)

            val response =
                client.execute(
                    request(HttpMethod.POST, "/provider").copy(
                        bodyProvider = provider,
                        contentType = "application/octet-stream",
                    ),
                )

            assertEquals(202, response.statusCode)
            assertEquals(payload.size.toString(), capturedLength.get())
            assertArrayEquals(payload, capturedBody.get())
        }

    @Test
    fun `streaming outbound provider removes supplied content length`() =
        runBlocking {
            val capturedLength = AtomicReference<String?>()
            val capturedEncoding = AtomicReference<String?>()
            val capturedBody = AtomicReference<ByteArray>()
            origin.createContext("/streaming-provider-framing") { exchange ->
                capturedLength.set(exchange.requestHeaders.getFirst("Content-Length"))
                capturedEncoding.set(exchange.requestHeaders.getFirst("Transfer-Encoding"))
                capturedBody.set(exchange.requestBody.use { it.readAllBytes() })
                exchange.respond(202)
            }
            val payload = "provider-payload".toByteArray()

            val response = client.execute(
                request(HttpMethod.POST, "/streaming-provider-framing").copy(
                    headers = mapOf("Content-Length" to "1"),
                    bodyProvider = RepeatableInMemoryCursorStreamProvider.fromBytes(payload),
                    requestStreamingMode = HttpStreamingMode.ALWAYS,
                ),
            )

            assertEquals(202, response.statusCode)
            assertNull(capturedLength.get())
            assertEquals("chunked", capturedEncoding.get())
            assertArrayEquals(payload, capturedBody.get())
        }

    @Test
    fun `streaming outbound bytes remove supplied content length`() =
        runBlocking {
            val capturedLength = AtomicReference<String?>()
            val capturedEncoding = AtomicReference<String?>()
            val capturedBody = AtomicReference<ByteArray>()
            origin.createContext("/streaming-bytes-framing") { exchange ->
                capturedLength.set(exchange.requestHeaders.getFirst("Content-Length"))
                capturedEncoding.set(exchange.requestHeaders.getFirst("Transfer-Encoding"))
                capturedBody.set(exchange.requestBody.use { it.readAllBytes() })
                exchange.respond(202)
            }
            val payload = "byte-payload".toByteArray()

            val response = client.execute(
                request(HttpMethod.POST, "/streaming-bytes-framing").copy(
                    headers = mapOf("Content-Length" to "1"),
                    body = payload,
                    requestStreamingMode = HttpStreamingMode.ALWAYS,
                ),
            )

            assertEquals(202, response.statusCode)
            assertNull(capturedLength.get())
            assertEquals("chunked", capturedEncoding.get())
            assertArrayEquals(payload, capturedBody.get())
        }

    @Test
    fun `streaming outbound provider closes its cursor when the transfer completes`() {
        runBlocking {
            origin.createContext("/cursor-lifecycle") { exchange ->
                exchange.requestBody.use { it.readAllBytes() }
                exchange.respond(202)
            }
            val provider = TrackingCursorStreamProvider(RepeatableInMemoryCursorStreamProvider.fromBytes("cursor-lifecycle".toByteArray()))

            val response =
                client.execute(
                    request(HttpMethod.POST, "/cursor-lifecycle").copy(
                        bodyProvider = provider,
                        contentType = "application/octet-stream",
                        requestStreamingMode = HttpStreamingMode.ALWAYS,
                    ),
                )

            assertEquals(202, response.statusCode)
            val transferCursor = provider.openedCursors.single()
            assertFalse(transferCursor.isReleased)
            assertThrows(StreamingException::class.java) { transferCursor.read() }
            response.bodyProvider?.releaseResources()
        }
    }

    @Test
    fun `connection response timeout is used when request has no override`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        origin.createContext("/config-timeout") { exchange ->
            entered.countDown()
            try {
                release.await(SERVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                exchange.respond(200)
            } finally {
                exchange.close()
            }
        }
        replaceClient(connectionConfig(responseTimeoutMs = SHORT_TIMEOUT_MS))

        val failure =
            try {
                assertThrows(Exception::class.java) {
                    runBlocking { client.execute(request(HttpMethod.GET, "/config-timeout")) }
                }
            } finally {
                assertTrue(entered.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                release.countDown()
            }

        assertTrue(failure.causeChain().any { it.message.orEmpty().contains("timeout", ignoreCase = true) })
    }

    @Test
    fun `request timeout override replaces the connection response timeout`() {
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            origin.createContext("/request-timeout") { exchange ->
                entered.countDown()
                check(release.await(SERVER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                exchange.respond(200, "completed".toByteArray())
            }
            replaceClient(connectionConfig(responseTimeoutMs = SHORT_TIMEOUT_MS))
            scheduler.schedule(release::countDown, OVERRIDE_RELEASE_DELAY_MS, TimeUnit.MILLISECONDS)

            val response =
                client.execute(
                    request(HttpMethod.GET, "/request-timeout").copy(
                        responseTimeoutMs = LONG_TIMEOUT_MS,
                    ),
                )

            assertTrue(entered.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(200, response.statusCode)
            assertArrayEquals("completed".toByteArray(), response.bodyProvider?.openCursor()?.use { it.readBytes() })
            response.bodyProvider?.releaseResources()
        }
    }

    @Test
    fun `connection refusal propagates a comprehensible transport error`() {
        val unusedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        replaceClient(connectionConfig(port = unusedPort, responseTimeoutMs = LONG_TIMEOUT_MS))

        val failure =
            assertThrows(Exception::class.java) {
                runBlocking { client.execute(request(HttpMethod.GET, "/refused", port = unusedPort)) }
            }

        assertTrue(
            failure.causeChain().any { cause ->
                cause.javaClass.simpleName.contains("Connect", ignoreCase = true) ||
                    cause.message.orEmpty().contains("refused", ignoreCase = true)
            },
            "Expected a connection error, got ${failure.causeChain().joinToString { it.javaClass.name + ": " + it.message }}",
        )
    }

    @Test
    fun `query parameters are encoded exactly once`() =
        runBlocking {
            val rawQuery = AtomicReference<String>()
            origin.createContext("/query") { exchange ->
                rawQuery.set(exchange.requestURI.rawQuery)
                exchange.respond(200)
            }
            val key = "key space"
            val value = "a b&c/d?e=f+g%2F"

            client.execute(
                request(HttpMethod.GET, "/query").copy(
                    queryParams = mapOf(key to value),
                ),
            )

            val encoded = checkNotNull(rawQuery.get())
            val encodedKey = encoded.substringBefore("=")
            val encodedValue = encoded.substringAfter("=")
            assertEquals(key, URLDecoder.decode(encodedKey, StandardCharsets.UTF_8))
            assertEquals(value, URLDecoder.decode(encodedValue, StandardCharsets.UTF_8))
            assertFalse(encoded.contains("&c/"))
        }

    @Test
    fun `already encoded path and query remain encoded once on the wire`() =
        runBlocking {
            val rawPath = AtomicReference<String>()
            val rawQuery = AtomicReference<String>()
            origin.createContext("/") { exchange ->
                rawPath.set(exchange.requestURI.rawPath)
                rawQuery.set(exchange.requestURI.rawQuery)
                exchange.respond(200)
            }

            val response = client.execute(
                request(HttpMethod.GET, "/encoded/line%20item%2Fsegment?existing=a%20b").copy(
                    queryParams = mapOf("added" to "c d"),
                ),
            )

            assertEquals(200, response.statusCode)
            assertEquals("/encoded/line%20item%2Fsegment", rawPath.get())
            assertEquals("existing=a%20b&added=c%20d", rawQuery.get())
        }

    @Test
    fun `close is idempotent and does not close the shared Vertx instance`() {
        client.close()
        client.close()

        val timerCompleted = CountDownLatch(1)
        vertx.setTimer(1) { timerCompleted.countDown() }

        assertTrue(timerCompleted.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val failure =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { client.execute(request(HttpMethod.GET, "/closed")) }
            }
        assertEquals("Quarkus HTTP client is closed", failure.message)
    }

    private fun replaceClient(config: HttpRequestConnectionConfig) {
        client.close()
        client = QuarkusWebClientHttpClient(vertx, config)
    }

    private fun connectionConfig(
        port: Int = origin.address.port,
        responseTimeoutMs: Long = LONG_TIMEOUT_MS,
    ): HttpRequestConnectionConfig =
        HttpRequestConnectionConfig(
            name = "behavior-client",
            host = LOOPBACK,
            port = port,
            connectionIdleTimeoutMs = LONG_TIMEOUT_MS,
            responseTimeoutMs = responseTimeoutMs,
            maxConnections = 2,
        )

    private fun request(
        method: HttpMethod,
        path: String,
        port: Int = origin.address.port,
    ): HttpOutboundRequest =
        HttpOutboundRequest(
            method = method,
            url = "http://$LOOPBACK:$port$path",
        )

    private fun HttpExchange.respond(
        status: Int,
        body: ByteArray? = null,
    ) {
        sendResponseHeaders(status, body?.size?.toLong() ?: -1L)
        body?.let { responseBody.use { output -> output.write(it) } } ?: responseBody.close()
        close()
    }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private class TrackingCursorStreamProvider(
        private val delegate: RepeatableInMemoryCursorStreamProvider,
    ) : MuseCursorStreamProvider {
        val openedCursors = mutableListOf<MuseCursorStream>()

        override val isClosed: Boolean
            get() = delegate.isClosed

        override val size: Long
            get() = delegate.size

        override fun openCursor(): MuseCursorStream =
            delegate.openCursor().also { openedCursors += it }

        override fun close() {
            delegate.close()
        }

        override fun releaseResources() {
            delegate.releaseResources()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CLOSE_TIMEOUT_SECONDS = 10L
        const val SERVER_TIMEOUT_SECONDS = 5L
        const val SHORT_TIMEOUT_MS = 100L
        const val OVERRIDE_RELEASE_DELAY_MS = 300L
        const val LONG_TIMEOUT_MS = 2_000L
    }
}

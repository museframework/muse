package dev.museframework.connectors.http.quarkus

import dev.museframework.engine.api.streaming.MuseCursor
import dev.museframework.engine.api.streaming.MuseCursorProvider
import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.engine.api.streaming.StreamingStrategy
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpResponse
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
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
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.name

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuarkusHttpServerBehaviorIT {
    private lateinit var vertx: Vertx
    private lateinit var transport: io.vertx.core.http.HttpServer
    private lateinit var server: QuarkusHttpServer
    private lateinit var endpoint: URI
    private val client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private val streamingTempDirectory: Path = Files.createTempDirectory("muse-quarkus-http-it")

    @BeforeAll
    fun startTransport() {
        vertx = Vertx.vertx()
        val router = Router.router(vertx)
        server = QuarkusHttpServer().also { it.router = router }
        transport = vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK).await()
        endpoint = URI.create("http://$LOOPBACK:${transport.actualPort()}/")
    }

    @BeforeEach
    fun startAdapter() {
        runBlocking { server.start() }
    }

    @AfterEach
    fun stopAdapter() {
        runBlocking { server.stop() }
        server.configureStreaming(
            StreamingStrategy.RepeatableFileStore(),
            StreamingStrategy.RepeatableFileStore(),
            legacyBodyMaterialization = false,
        )
        streamingTempFiles().forEach { Files.deleteIfExists(it) }
    }

    @AfterAll
    fun stopTransport() {
        if (::transport.isInitialized) transport.close().await()
        if (::vertx.isInitialized) vertx.close().await()
        Files.deleteIfExists(streamingTempDirectory)
    }

    @Test
    fun `matches double wildcard single wildcard and placeholder paths`() {
        server.register("/paths/deep/**", listOf(HttpMethod.GET)) { request ->
            HttpResponse(statusCode = 200, body = request.requestPath.toByteArray())
        }
        server.register("/paths/item/*/detail", listOf(HttpMethod.GET)) { request ->
            HttpResponse(statusCode = 200, body = request.requestPath.toByteArray())
        }
        server.register("/paths/order/{id}", listOf(HttpMethod.GET)) { request ->
            HttpResponse(statusCode = 200, body = request.requestPath.toByteArray())
        }

        assertEquals("/paths/deep/a/b", execute("GET", "/paths/deep/a/b").body().decodeToString())
        assertEquals("/paths/item/42/detail", execute("GET", "/paths/item/42/detail").body().decodeToString())
        assertEquals("/paths/order/7", execute("GET", "/paths/order/7").body().decodeToString())
        assertEquals(404, execute("GET", "/paths/item/a/b/detail").statusCode())
    }

    @Test
    fun `absolute-form request target exposes only path and query metadata`() {
        val recorded = AtomicReference<dev.museframework.http.api.model.HttpRequest?>()
        server.register("/metadata/absolute/{id}", listOf(HttpMethod.GET)) { request ->
            recorded.set(request)
            HttpResponse(statusCode = 200, body = "ok".toByteArray())
        }
        val absoluteTarget = "http://$LOOPBACK:${transport.actualPort()}/metadata/absolute/line%20item+plus?expand=line%20items+plus"
        val response = executeRawGet(absoluteTarget)
        assertTrue(response.startsWith("HTTP/1.1 200"))
        val request = checkNotNull(recorded.get())
        assertEquals("/metadata/absolute/line item+plus", request.requestPath)
        assertEquals("/metadata/absolute/line%20item+plus", request.rawRequestPath)
        assertEquals("/metadata/absolute/line item+plus?expand=line items+plus", request.requestUri)
        assertEquals("/metadata/absolute/line%20item+plus?expand=line%20items+plus", request.rawRequestUri)
        assertEquals("expand=line items+plus", request.queryString)
        assertEquals("line items plus", request.queryParams["expand"])
        assertEquals("line item+plus", request.uriParams["id"])
        assertFalse(request.requestPath.contains("http://"))
        assertFalse(request.rawRequestPath.contains("http://"))
    }

    @Test
    fun `ANY handler accepts multiple methods`() {
        server.register("/methods/any", listOf(HttpMethod.ANY)) { request ->
            HttpResponse(statusCode = 200, body = request.method.name.toByteArray())
        }

        assertEquals("GET", execute("GET", "/methods/any").body().decodeToString())
        assertEquals("POST", execute("POST", "/methods/any", "body".toByteArray()).body().decodeToString())
    }

    @Test
    fun `failed handler returns one error response and leaves independent routes active`() {
        val failures = AtomicInteger()
        server.register("/errors/fail", listOf(HttpMethod.GET)) {
            failures.incrementAndGet()
            error("simulated failure")
        }
        server.register("/errors/healthy", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, body = "healthy".toByteArray())
        }

        val failed = execute("GET", "/errors/fail")
        val healthy = execute("GET", "/errors/healthy")

        assertEquals(500, failed.statusCode())
        assertEquals("Internal Server Error", failed.body().decodeToString())
        assertEquals(1, failures.get())
        assertEquals(200, healthy.statusCode())
        assertEquals("healthy", healthy.body().decodeToString())
    }

    @Test
    fun `stream limit failure returns payload too large without invoking handler`() {
        val invoked = AtomicBoolean(false)
        server.configureStreaming(
            StreamingStrategy.RepeatableInMemory(
                initialBufferSizeBytes = 4,
                bufferSizeIncrementBytes = 4,
                maxBufferSizeBytes = 8,
            ),
            StreamingStrategy.RepeatableFileStore(),
            legacyBodyMaterialization = false,
        )
        server.register("/stream/limited", listOf(HttpMethod.POST)) {
            invoked.set(true)
            HttpResponse(statusCode = 200)
        }

        val response = execute("POST", "/stream/limited", "larger-than-eight".toByteArray())

        assertEquals(413, response.statusCode())
        assertFalse(invoked.get())
    }

    @Test
    fun `writes and releases response provider`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("stream-response".toByteArray())
        server.register("/stream/response", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        val response = execute("GET", "/stream/response")

        assertEquals(200, response.statusCode())
        assertEquals("stream-response", response.body().decodeToString())
        assertClosed(provider)
    }

    @Test
    fun `auto provider response sends content length and full body`() {
        val payload = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes(payload)
        server.register("/stream/auto", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        val response = executeHttp11("GET", "/stream/auto")

        assertEquals(200, response.statusCode())
        assertArrayEquals(payload, response.body())
        assertEquals(payload.size.toString(), response.headers().firstValue("Content-Length").orElse(null))
        assertClosed(provider)
    }

    @Test
    fun `always provider response streams chunked`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("chunked-provider".toByteArray())
        server.register("/stream/always-provider", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider, streamingMode = HttpStreamingMode.ALWAYS)
        }

        val response = executeHttp11("GET", "/stream/always-provider")

        assertArrayEquals("chunked-provider".toByteArray(), response.body())
        assertEquals("chunked", response.headers().firstValue("Transfer-Encoding").orElse(null))
        assertClosed(provider)
    }

    @Test
    fun `auto provider with unknown size streams chunked`() {
        val provider =
            UnknownSizeProvider(RepeatableInMemoryCursorStreamProvider.fromBytes("unknown-size".toByteArray()))
        server.register("/stream/auto-unknown", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        val response = executeHttp11("GET", "/stream/auto-unknown")

        assertArrayEquals("unknown-size".toByteArray(), response.body())
        assertEquals("chunked", response.headers().firstValue("Transfer-Encoding").orElse(null))
        assertClosed(provider)
    }

    @Test
    fun `HTTP 1 1 chunked provider response removes supplied content length`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("chunked-provider".toByteArray())
        server.register("/framing/provider", listOf(HttpMethod.GET)) {
            HttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Length" to "1"),
                bodyProvider = provider,
                streamingMode = HttpStreamingMode.ALWAYS,
            )
        }

        val wire = executeRawGet("/framing/provider")
        val headers = wire.substringBefore("\r\n\r\n")

        assertTrue(headers.startsWith("HTTP/1.1 200"))
        assertTrue(headers.contains("Transfer-Encoding: chunked", ignoreCase = true))
        assertFalse(headers.contains("Content-Length:", ignoreCase = true))
        assertTrue(wire.contains("chunked-provider"))
        assertClosed(provider)
    }

    @Test
    fun `HTTP 1 1 chunked byte response removes supplied content length`() {
        server.register("/framing/bytes", listOf(HttpMethod.GET)) {
            HttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Length" to "1"),
                body = "chunked-bytes".toByteArray(),
                streamingMode = HttpStreamingMode.ALWAYS,
            )
        }

        val wire = executeRawGet("/framing/bytes")
        val headers = wire.substringBefore("\r\n\r\n")

        assertTrue(headers.contains("Transfer-Encoding: chunked", ignoreCase = true))
        assertFalse(headers.contains("Content-Length:", ignoreCase = true))
        assertTrue(wire.contains("chunked-bytes"))
    }

    @Test
    fun `HTTP 1 0 always provider uses known length`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("known-length".toByteArray())
        server.register("/framing/http10-known", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider, streamingMode = HttpStreamingMode.ALWAYS)
        }

        val wire = executeRawGet("/framing/http10-known", "HTTP/1.0")
        val headers = wire.substringBefore("\r\n\r\n")

        assertTrue(headers.startsWith("HTTP/1.0 200"))
        assertTrue(headers.contains("Content-Length: 12", ignoreCase = true))
        assertFalse(headers.contains("Transfer-Encoding:", ignoreCase = true))
        assertEquals("known-length", wire.substringAfter("\r\n\r\n"))
        assertClosed(provider)
    }

    @Test
    fun `HTTP 1 0 always byte response uses actual length`() {
        server.register("/framing/http10-bytes", listOf(HttpMethod.GET)) {
            HttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Length" to "1"),
                body = "known-bytes".toByteArray(),
                streamingMode = HttpStreamingMode.ALWAYS,
            )
        }

        val wire = executeRawGet("/framing/http10-bytes", "HTTP/1.0")
        val headers = wire.substringBefore("\r\n\r\n")

        assertTrue(headers.contains("Content-Length: 11", ignoreCase = true))
        assertFalse(headers.contains("Transfer-Encoding:", ignoreCase = true))
        assertEquals("known-bytes", wire.substringAfter("\r\n\r\n"))
    }

    @Test
    fun `HTTP 1 0 unknown provider uses connection close framing`() {
        val provider = UnknownSizeProvider(RepeatableInMemoryCursorStreamProvider.fromBytes("unknown-size".toByteArray()))
        server.register("/framing/http10-unknown", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        val wire = executeRawGet("/framing/http10-unknown", "HTTP/1.0")
        val headers = wire.substringBefore("\r\n\r\n")

        assertTrue(headers.startsWith("HTTP/1.0 200"))
        assertFalse(headers.contains("Content-Length:", ignoreCase = true))
        assertFalse(headers.contains("Transfer-Encoding:", ignoreCase = true))
        assertEquals("unknown-size", wire.substringAfter("\r\n\r\n"))
        assertClosed(provider)
    }

    @Test
    fun `HTTP 1 0 unknown provider closes a requested persistent connection`() {
        val provider = UnknownSizeProvider(RepeatableInMemoryCursorStreamProvider.fromBytes("close-framed".toByteArray()))
        server.register("/framing/http10-close", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        val wire = executeRawGet("/framing/http10-close", "HTTP/1.0", "keep-alive")
        val headers = wire.substringBefore("\r\n\r\n")

        assertFalse(headers.contains("Content-Length:", ignoreCase = true))
        assertFalse(headers.contains("Transfer-Encoding:", ignoreCase = true))
        assertEquals("close-framed", wire.substringAfter("\r\n\r\n"))
        assertClosed(provider)
    }

    @Test
    fun `HTTP 1 0 closes persistent connection when provider size becomes known during transfer`() {
        val provider = SizeBecomesKnownProvider(RepeatableInMemoryCursorStreamProvider.fromBytes("late-size".toByteArray()))
        server.register("/framing/http10-late-size", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        val wire = executeRawGet("/framing/http10-late-size", "HTTP/1.0", "keep-alive")
        val headers = wire.substringBefore("\r\n\r\n")

        assertFalse(headers.contains("Content-Length:", ignoreCase = true))
        assertFalse(headers.contains("Transfer-Encoding:", ignoreCase = true))
        assertEquals("late-size", wire.substringAfter("\r\n\r\n"))
        assertEquals(9L, provider.size)
        assertClosed(provider)
    }

    @Test
    fun `mid-stream provider failure closes the transfer and releases provider once`() {
        val provider = FailingProvider()
        server.register("/stream/mid-failure", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        assertThrows(IOException::class.java) { executeHttp11("GET", "/stream/mid-failure") }

        assertEquals(1, provider.releasedCount.get())
    }

    @Test
    fun `mid-stream failure closes the retained lease without releasing the provider directly`() {
        val provider = FailingProvider()
        val owner = StreamingResourceRegistry()
        owner.register(provider)
        val lease = owner.retain(listOf(provider))
        server.register("/stream/mid-failure-leased", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider, bodyLease = lease)
        }

        try {
            assertThrows(IOException::class.java) { executeHttp11("GET", "/stream/mid-failure-leased") }
            assertEquals(0, provider.closedCount.get(), "the owner still holds the provider")
            assertEquals(0, provider.releasedCount.get(), "the adapter must not release the provider directly")

            owner.releaseAll()
            assertTrue(
                provider.releaseCompleted.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the failed response must finish closing its lease",
            )
            assertEquals(1, provider.closedCount.get(), "the failed response must have closed its lease")
            assertEquals(1, provider.releasedCount.get(), "last-owner cleanup releases once")
        } finally {
            owner.releaseAll()
            lease.close()
        }
    }

    @Test
    fun `streaming response cleanup goes through the retained lease`() {
        val delegate = RepeatableInMemoryCursorStreamProvider.fromBytes("leased-response".toByteArray())
        val closes = AtomicInteger()
        val releases = AtomicInteger()
        val provider =
            object : MuseCursorStreamProvider by delegate {
                override fun close() {
                    closes.incrementAndGet()
                    delegate.close()
                }

                override fun releaseResources() {
                    releases.incrementAndGet()
                    delegate.releaseResources()
                }
            }
        // The reply owner exports exactly one retained claim; the adapter must close that, never
        // the provider, so the provider's own lifecycle runs once, as last-owner cleanup.
        val lease = StreamingResourceRegistry.ownedClaim(provider)
        server.register("/stream/leased-response", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider, bodyLease = lease)
        }

        val response = execute("GET", "/stream/leased-response")

        assertEquals(200, response.statusCode())
        assertEquals("leased-response", response.body().decodeToString())
        assertTrue(provider.isClosed)
        assertEquals(1, closes.get(), "The adapter closes the retained lease, not the provider")
        assertEquals(1, releases.get(), "Last-owner cleanup runs once, through the lease")
    }

    @Test
    fun `a response setup failure closes exactly the lease the response owns`() {
        val delegate = RepeatableInMemoryCursorStreamProvider.fromBytes("setup-failure".toByteArray())
        val closes = AtomicInteger()
        val releases = AtomicInteger()
        val provider =
            object : MuseCursorStreamProvider by delegate {
                override val size: Long
                    get() = throw IllegalStateException("content length unavailable")

                override fun close() {
                    closes.incrementAndGet()
                    delegate.close()
                }

                override fun releaseResources() {
                    releases.incrementAndGet()
                    delegate.releaseResources()
                }
            }
        server.register("/stream/setup-failure", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        val response = execute("GET", "/stream/setup-failure")

        assertEquals(500, response.statusCode())
        assertTrue(
            provider.isClosed,
            "the lease this response owns releases its provider when setup fails",
        )
        assertEquals(1, closes.get(), "exactly one lease closes for this response")
        assertEquals(1, releases.get(), "cleanup runs once, through that lease")
    }

    @Test
    fun `streaming IO failure returns error and releases provider`() {
        val released = AtomicBoolean(false)
        val provider =
            object : MuseCursorStreamProvider {
                override val isClosed: Boolean
                    get() = released.get()

                override fun openCursor(): MuseCursorStream = throw IOException("simulated stream failure")

                override fun close() {
                    released.set(true)
                }

                override fun releaseResources() {
                    released.set(true)
                }
            }
        server.register("/stream/failure", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        val response = execute("GET", "/stream/failure")

        assertEquals(500, response.statusCode())
        assertEquals("Unable to process HTTP body", response.body().decodeToString())
        assertTrue(released.get())
    }

    @Test
    fun `releases inbound file store and deletes spool file`() {
        val providerRef = AtomicReference<MuseCursorStreamProvider>()
        server.configureStreaming(
            StreamingStrategy.RepeatableFileStore(
                inMemorySizeBytes = 4,
                tempDirectory = streamingTempDirectory.toString(),
                deleteFilesOnClose = true,
            ),
            StreamingStrategy.RepeatableFileStore(),
            legacyBodyMaterialization = false,
        )
        server.register("/stream/spool", listOf(HttpMethod.POST)) { request ->
            val provider = requireNotNull(request.bodyProvider)
            providerRef.set(provider)
            HttpResponse(statusCode = 200, body = provider.openCursor().use { it.readBytes() })
        }

        val payload = "payload-large-enough-to-spill".toByteArray()
        val response = execute("POST", "/stream/spool", payload)

        assertArrayEquals(payload, response.body())
        assertTrue(providerRef.get().isClosed)
        assertTrue(streamingTempFiles().isEmpty())
    }

    @Test
    fun `detaches and cleans a non-repeatable request echo`() {
        server.configureStreaming(
            StreamingStrategy.NonRepeatable,
            StreamingStrategy.RepeatableFileStore(
                inMemorySizeBytes = 4,
                tempDirectory = streamingTempDirectory.toString(),
                deleteFilesOnClose = true,
            ),
            legacyBodyMaterialization = false,
        )
        server.register("/stream/echo", listOf(HttpMethod.POST)) { request ->
            HttpResponse(statusCode = 200, bodyProvider = request.bodyProvider)
        }
        val payload = ByteArray(64 * 1024) { index -> (index % 251).toByte() }

        val response = execute("POST", "/stream/echo", payload)

        assertEquals(200, response.statusCode())
        assertArrayEquals(payload, response.body())
        assertTrue(streamingTempFiles().isEmpty())
    }

    @Test
    fun `a failing superseded lease still releases the detached provider`() {
        server.configureStreaming(
            StreamingStrategy.NonRepeatable,
            StreamingStrategy.RepeatableFileStore(
                inMemorySizeBytes = 4,
                tempDirectory = streamingTempDirectory.toString(),
                deleteFilesOnClose = true,
            ),
            legacyBodyMaterialization = false,
        )
        val supersededProvider = ThrowingReleaseProvider()
        val supersededLease = StreamingResourceRegistry.ownedClaim(supersededProvider)
        server.register("/stream/doomed-lease", listOf(HttpMethod.POST)) { request ->
            HttpResponse(
                statusCode = 200,
                bodyProvider = request.bodyProvider,
                bodyLease = supersededLease,
            )
        }

        val payload = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        val response = execute("POST", "/stream/doomed-lease", payload)

        assertTrue(
            response.statusCode() !in 200..299,
            "the superseded-claim failure propagates instead of writing a response",
        )
        assertTrue(
            streamingTempFiles().isEmpty(),
            "the detached provider's claim is released even when closing the superseded lease fails",
        )
        assertTrue(supersededProvider.isClosed, "the failing superseded claim still ended its lifecycle")
        assertEquals(1, supersededProvider.releases)
    }

    @Test
    fun `empty request does not create artificial body`() {
        val providerRef = AtomicReference<MuseCursorStreamProvider?>()
        val bodyRef = AtomicReference<ByteArray?>()
        server.register("/stream/empty", listOf(HttpMethod.POST)) { request ->
            providerRef.set(request.bodyProvider)
            bodyRef.set(request.body)
            HttpResponse(statusCode = 204)
        }

        val response = execute("POST", "/stream/empty", ByteArray(0))

        assertEquals(204, response.statusCode())
        assertNull(providerRef.get())
        assertNull(bodyRef.get())
    }

    @Test
    fun `duplicate registration replaces the previous handler`() {
        val previousInvocations = AtomicInteger()
        val currentInvocations = AtomicInteger()
        server.register("/lifecycle/duplicate", listOf(HttpMethod.GET)) {
            previousInvocations.incrementAndGet()
            HttpResponse(statusCode = 200, body = "previous".toByteArray())
        }
        server.register("/lifecycle/duplicate", listOf(HttpMethod.GET)) {
            currentInvocations.incrementAndGet()
            HttpResponse(statusCode = 200, body = "current".toByteArray())
        }

        val response = execute("GET", "/lifecycle/duplicate")

        assertEquals("current", response.body().decodeToString())
        assertEquals(0, previousInvocations.get())
        assertEquals(1, currentInvocations.get())
    }

    @Test
    fun `unregister lets in-flight request finish and blocks following requests`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val methods = listOf(HttpMethod.GET)
        server.register("/lifecycle/in-flight", methods) {
            entered.countDown()
            check(release.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            HttpResponse(statusCode = 200, body = "completed".toByteArray())
        }

        val inFlight = executeAsync("GET", "/lifecycle/in-flight")
        assertTrue(entered.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        server.unregister("/lifecycle/in-flight", methods)

        try {
            assertEquals(404, execute("GET", "/lifecycle/in-flight").statusCode())
        } finally {
            release.countDown()
        }
        assertEquals("completed", inFlight.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).body().decodeToString())
    }

    @Test
    fun `stop removes routes and start creates a usable handler scope`() {
        val invocations = AtomicInteger()
        server.register("/lifecycle/restart", listOf(HttpMethod.GET)) {
            invocations.incrementAndGet()
            HttpResponse(statusCode = 200)
        }

        runBlocking { server.stop() }
        assertEquals(404, execute("GET", "/lifecycle/restart").statusCode())
        assertEquals(0, invocations.get())

        runBlocking { server.start() }
        server.register("/lifecycle/restart", listOf(HttpMethod.GET)) {
            invocations.incrementAndGet()
            HttpResponse(statusCode = 202)
        }
        assertEquals(202, execute("GET", "/lifecycle/restart").statusCode())
        assertEquals(1, invocations.get())
    }

    @Test
    fun `concurrent routes keep request state isolated`() {
        val barrier = CyclicBarrier(2)
        server.register("/concurrent/a", listOf(HttpMethod.GET)) { request ->
            barrier.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            HttpResponse(statusCode = 200, body = "A:${request.requestPath}".toByteArray())
        }
        server.register("/concurrent/b", listOf(HttpMethod.GET)) { request ->
            barrier.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            HttpResponse(statusCode = 200, body = "B:${request.requestPath}".toByteArray())
        }

        val first = executeAsync("GET", "/concurrent/a")
        val second = executeAsync("GET", "/concurrent/b")

        assertEquals("A:/concurrent/a", first.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).body().decodeToString())
        assertEquals("B:/concurrent/b", second.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).body().decodeToString())
    }

    @Test
    fun `concurrent registration and removal preserve every route`() {
        val routeCount = 16
        val methods = listOf(HttpMethod.GET)
        val registrations =
            (0 until routeCount).map { index ->
                CompletableFuture.runAsync {
                    server.register("/registry/$index", methods) {
                        HttpResponse(statusCode = 200, body = index.toString().toByteArray())
                    }
                }
            }
        CompletableFuture.allOf(*registrations.toTypedArray()).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        repeat(routeCount) { index ->
            assertEquals(index.toString(), execute("GET", "/registry/$index").body().decodeToString())
        }

        val removals =
            (0 until routeCount).map { index ->
                CompletableFuture.runAsync { server.unregister("/registry/$index", methods) }
            }
        CompletableFuture.allOf(*removals.toTypedArray()).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        repeat(routeCount) { index ->
            assertEquals(404, execute("GET", "/registry/$index").statusCode())
        }
    }

    private fun execute(
        method: String,
        path: String,
        body: ByteArray? = null,
    ): java.net.http.HttpResponse<ByteArray> =
        client.send(request(method, path, body), BodyHandlers.ofByteArray())

    private fun executeAsync(
        method: String,
        path: String,
        body: ByteArray? = null,
    ): CompletableFuture<java.net.http.HttpResponse<ByteArray>> =
        client.sendAsync(request(method, path, body), BodyHandlers.ofByteArray())

    private fun request(method: String, path: String, body: ByteArray?): HttpRequest =
        HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .method(
                method,
                body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody(),
            ).build()

    private fun executeHttp11(
        method: String,
        path: String,
        body: ByteArray? = null,
    ): java.net.http.HttpResponse<ByteArray> =
        client.send(requestHttp11(method, path, body), BodyHandlers.ofByteArray())

    private fun requestHttp11(method: String, path: String, body: ByteArray?): HttpRequest =
        HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .method(
                method,
                body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody(),
            ).build()

    private fun assertClosed(provider: MuseCursorStreamProvider) {
        val deadline = System.nanoTime() + CLOSE_TIMEOUT_SECONDS * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            if (provider.isClosed) return
            Thread.sleep(10)
        }
        assertTrue(provider.isClosed)
    }

    private fun streamingTempFiles(): List<Path> =
        Files.list(streamingTempDirectory).use { paths ->
            paths.filter { it.name.startsWith("muse-stream-") }.toList()
        }

    private fun executeRawGet(
        requestTarget: String,
        version: String = "HTTP/1.1",
        connection: String = "close",
    ): String =
        java.net.Socket(LOOPBACK, transport.actualPort()).use { socket ->
            socket.soTimeout = REQUEST_TIMEOUT.toMillis().toInt()
            val wireRequest =
                "GET $requestTarget $version\r\n" +
                    "Host: $LOOPBACK:${transport.actualPort()}\r\n" +
                    "Connection: $connection\r\n\r\n"
            socket.getOutputStream().apply {
                write(wireRequest.toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            socket.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        }

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private class UnknownSizeProvider(
        private val delegate: RepeatableInMemoryCursorStreamProvider,
    ) : MuseCursorStreamProvider by delegate {
        override val size: Long = -1L
    }

    private class SizeBecomesKnownProvider(
        private val delegate: RepeatableInMemoryCursorStreamProvider,
    ) : MuseCursorStreamProvider by delegate {
        @Volatile
        private var consumed = false

        override val size: Long
            get() = if (consumed) delegate.size else -1L

        override fun openCursor(): MuseCursorStream {
            val cursor = delegate.openCursor()
            return object : MuseCursorStream() {
                override val position: Long
                    get() = cursor.position
                override val isReleased: Boolean
                    get() = cursor.isReleased
                override val provider: MuseCursorProvider<out MuseCursor> = this@SizeBecomesKnownProvider

                override fun read(): Int = cursor.read().also { if (it == -1) consumed = true }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    cursor.read(buffer, offset, length).also { if (it == -1) consumed = true }

                override fun seek(position: Long) = cursor.seek(position)

                override fun release() = cursor.release()

                override fun close() = cursor.close()
            }
        }
    }

    private class FailingProvider : MuseCursorStreamProvider {
        val closedCount = AtomicInteger(0)
        val releasedCount = AtomicInteger(0)
        val releaseCompleted = CountDownLatch(1)
        override val isClosed: Boolean
            get() = closedCount.get() > 0
        override val size: Long = -1L
        override fun openCursor(): MuseCursorStream = FailingCursor(this)
        override fun close() {
            closedCount.incrementAndGet()
        }
        override fun releaseResources() {
            releasedCount.incrementAndGet()
            releaseCompleted.countDown()
        }
    }

    private class FailingCursor(private val owner: FailingProvider) : MuseCursorStream() {
        private var produced = 0
        override val position: Long
            get() = produced.toLong()
        override val isReleased: Boolean = false
        override val provider: MuseCursorProvider<out MuseCursor> = owner

        override fun read(): Int {
            if (produced > 0) throw IOException("simulated mid-stream failure")
            produced = 1
            return 'a'.code
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (produced > 0) throw IOException("simulated mid-stream failure")
            if (length == 0) return 0
            buffer[offset] = 'a'.code.toByte()
            produced = 1
            return 1
        }

        override fun seek(position: Long) {}

        override fun release() {}
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CLOSE_TIMEOUT_SECONDS = 10L
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/** A claim whose release always fails, so cleanup has to attach the failure and keep going. */
private class ThrowingReleaseProvider : MuseCursorStreamProvider {
    @Volatile
    override var isClosed: Boolean = false
        private set

    @Volatile
    var releases: Int = 0
        private set

    override val size: Long = -1

    override fun openCursor(): MuseCursorStream =
        error("this lifecycle double never provides a cursor")

    override fun close() {
        isClosed = true
    }

    override fun releaseResources() {
        releases += 1
        isClosed = true
        throw IllegalStateException("superseded claim release failed")
    }
}

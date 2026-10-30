package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.engine.api.streaming.StreamingStrategy
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.server.HttpServer
import dev.museframework.http.testkit.HttpServerContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.name
import kotlin.test.assertIs

@SpringBootTest(
    classes = [HttpConnectorSpringTestApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureTestRestTemplate
@TestPropertySource(
    properties = [
        "muse.streaming.file-store.in-memory-size-bytes=4",
        "muse.streaming.file-store.delete-files-on-close=true",
    ],
)
class SpringHttpServerIT : HttpServerContract() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var springHttpListenerServerInitializer: SpringHttpListenerServerInitializer

    @Autowired
    private lateinit var streamingProperties: MuseHttpStreamingProperties

    private fun server(): SpringHttpServer =
        springHttpListenerServerInitializer.initialize(
            HttpListenerConnectionConfig(
                name = "spring-server-it",
                host = "localhost",
                port = port,
            ),
        ) as SpringHttpServer

    override fun createServer(): HttpServer = server()

    override fun baseUri(): URI = URI.create("http://localhost:$port")

    @Test
    fun `should match path with Ant-style wildcard pattern`() {
        val server = server()
        server.register("/test/wildcard/**", listOf(HttpMethod.GET)) { request ->
            HttpResponse(
                statusCode = 200,
                body = "matched: ${request.requestPath}".toByteArray(),
            )
        }

        try {
            val response =
                restTemplate.getForEntity(
                    "http://localhost:$port/test/wildcard/foo/bar",
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("matched: /test/wildcard/foo/bar", response.body)
        } finally {
            server.unregister("/test/wildcard/**", listOf(HttpMethod.GET))
        }
    }

    @Test
    fun `should match path with single-segment wildcard`() {
        val server = server()
        server.register("/test/items/*/details", listOf(HttpMethod.GET)) { request ->
            HttpResponse(
                statusCode = 200,
                body = "item-details: ${request.requestPath}".toByteArray(),
            )
        }

        try {
            val response =
                restTemplate.getForEntity(
                    "http://localhost:$port/test/items/42/details",
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("item-details: /test/items/42/details", response.body)
        } finally {
            server.unregister("/test/items/*/details", listOf(HttpMethod.GET))
        }
    }

    @Test
    fun `should match ANY method handler for GET and POST`() {
        val server = server()
        server.register("/test/any-method", listOf(HttpMethod.ANY)) {
            HttpResponse(statusCode = 200, body = "any-method".toByteArray())
        }

        try {
            val getResponse =
                restTemplate.getForEntity(
                    "http://localhost:$port/test/any-method",
                    String::class.java,
                )
            assertEquals(HttpStatus.OK, getResponse.statusCode)

            val postResponse =
                restTemplate.postForEntity(
                    "http://localhost:$port/test/any-method",
                    "data",
                    String::class.java,
                )
            assertEquals(HttpStatus.OK, postResponse.statusCode)
        } finally {
            server.unregister("/test/any-method", listOf(HttpMethod.ANY))
        }
    }

    @Test
    fun `should return error status when handler throws exception`() {
        val server = server()
        server.register("/test/error", listOf(HttpMethod.GET)) {
            throw RuntimeException("Simulated handler error")
        }

        try {
            val response =
                restTemplate.getForEntity(
                    "http://localhost:$port/test/error",
                    String::class.java,
                )

            assertFalse(response.statusCode.is2xxSuccessful, "Expected non-2xx status when handler throws")
        } finally {
            server.unregister("/test/error", listOf(HttpMethod.GET))
        }
    }

    @Test
    fun `should pass request body provider to handler on POST`() {
        val server = server()
        server.register("/test/stream-request", listOf(HttpMethod.POST)) { request ->
            HttpResponse(
                statusCode = 200,
                body = request.bodyProvider?.openCursor()?.use { it.readBytes() } ?: ByteArray(0),
            )
        }

        try {
            val payload = "stream-request"
            val response =
                restTemplate.postForEntity(
                    "http://localhost:$port/test/stream-request",
                    payload,
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(payload, response.body)
        } finally {
            server.unregister("/test/stream-request", listOf(HttpMethod.POST))
        }
    }

    @Test
    fun `streaming response cleanup goes through the retained lease in every mode`() {
        val server = server()
        for (mode in HttpStreamingMode.entries) {
            val delegate = RepeatableInMemoryCursorStreamProvider.fromBytes("leased-response".toByteArray())
            val closes = AtomicInteger()
            val releases = AtomicInteger()
            val releaseCompleted = CountDownLatch(1)
            val provider =
                object : MuseCursorStreamProvider by delegate {
                    override fun close() {
                        closes.incrementAndGet()
                        delegate.close()
                    }

                    override fun releaseResources() {
                        releases.incrementAndGet()
                        delegate.releaseResources()
                        releaseCompleted.countDown()
                    }
                }
            val owner = StreamingResourceRegistry()
            owner.register(provider)
            val lease = owner.retain(listOf(provider))
            val path = "/test/leased-response-${mode.name.lowercase()}"
            server.register(path, listOf(HttpMethod.GET)) {
                HttpResponse(statusCode = 200, bodyProvider = provider, bodyLease = lease, streamingMode = mode)
            }

            try {
                val response = restTemplate.getForEntity("http://localhost:$port$path", String::class.java)

                assertEquals(HttpStatus.OK, response.statusCode, "mode=$mode")
                assertEquals("leased-response", response.body, "mode=$mode")
                assertFalse(provider.isClosed, "mode=$mode: the owner's claim still holds the provider")
                assertEquals(0, closes.get(), "mode=$mode: the adapter must not close the provider directly")
                assertEquals(0, releases.get(), "mode=$mode: the adapter must not release the provider directly")

                owner.releaseAll()
                assertTrue(releaseCompleted.await(5, TimeUnit.SECONDS), "mode=$mode: response lease cleanup must finish")
                assertTrue(provider.isClosed, "mode=$mode: the adapter must have closed its response lease")
                assertEquals(1, closes.get(), "mode=$mode: last-owner cleanup closes once")
                assertEquals(1, releases.get(), "mode=$mode: last-owner cleanup releases once")
            } finally {
                server.unregister(path, listOf(HttpMethod.GET))
                owner.releaseAll()
                lease.close()
            }
        }
    }

    @Test
    fun `always mode streams a byte array without a provider lease`() {
        val server = server()
        server.register("/test/always-bytes", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, body = "always-bytes".toByteArray(), streamingMode = HttpStreamingMode.ALWAYS)
        }

        try {
            val response = restTemplate.getForEntity("http://localhost:$port/test/always-bytes", String::class.java)

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("always-bytes", response.body)
            assertEquals("chunked", response.headers.getFirst("Transfer-Encoding"))
        } finally {
            server.unregister("/test/always-bytes", listOf(HttpMethod.GET))
        }
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
        val server = server()
        server.register("/test/setup-failure", listOf(HttpMethod.GET)) {
            HttpResponse(statusCode = 200, bodyProvider = provider)
        }

        try {
            val response =
                restTemplate.getForEntity(
                    "http://localhost:$port/test/setup-failure",
                    String::class.java,
                )

            assertFalse(response.statusCode.is2xxSuccessful, "response construction failed")
            assertTrue(
                provider.isClosed,
                "the lease this response owns releases its provider when construction fails",
            )
            assertEquals(1, closes.get(), "exactly one lease closes for this response")
            assertEquals(1, releases.get(), "cleanup runs once, through that lease")
        } finally {
            server.unregister("/test/setup-failure", listOf(HttpMethod.GET))
        }
    }

    @Test
    fun `should write streaming response body from handler`() {
        val delegate = RepeatableInMemoryCursorStreamProvider.fromBytes("stream-response".toByteArray())
        val releaseCompleted = CountDownLatch(1)
        val provider =
            object : MuseCursorStreamProvider by delegate {
                override fun releaseResources() {
                    delegate.releaseResources()
                    releaseCompleted.countDown()
                }
            }
        val server = server()
        server.register("/test/stream-response", listOf(HttpMethod.GET)) {
            HttpResponse(
                statusCode = 200,
                bodyProvider = provider,
            )
        }

        try {
            val response =
                restTemplate.getForEntity(
                    "http://localhost:$port/test/stream-response",
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("stream-response", response.body)
            assertTrue(
                releaseCompleted.await(5, TimeUnit.SECONDS),
                "The HTTP adapter must release a response provider after serialization",
            )
            assertTrue(provider.isClosed)
        } finally {
            server.unregister("/test/stream-response", listOf(HttpMethod.GET))
        }
    }

    @Test
    fun `should bind streaming file store properties`() {
        val strategy = streamingProperties.toStrategy()

        assertIs<StreamingStrategy.RepeatableFileStore>(strategy)
        assertEquals(4, strategy.inMemorySizeBytes)
        assertEquals(true, strategy.deleteFilesOnClose)
    }

    @Test
    fun `should spill inbound request to configured file store and delete file when provider is released`() {
        streamingTempFiles(streamingTempDir).forEach { Files.deleteIfExists(it) }
        val providerRef = AtomicReference<MuseCursorStreamProvider>()
        val server = server()
        server.register("/test/spill-cleanup", listOf(HttpMethod.POST)) { request ->
            val provider = request.bodyProvider ?: error("Expected bodyProvider")
            providerRef.set(provider)
            HttpResponse(
                statusCode = 200,
                body = provider.openCursor().use { it.readBytes() },
            )
        }

        try {
            val payload = "payload-large-enough-to-spill"
            val response =
                restTemplate.postForEntity(
                    "http://localhost:$port/test/spill-cleanup",
                    payload,
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(payload, response.body)
            assertEquals(payload.toByteArray().size.toLong(), providerRef.get().size)

            assertTrue(providerRef.get().isClosed, "The HTTP adapter must release the inbound provider")
            assertEquals(emptyList<Path>(), streamingTempFiles(streamingTempDir))
        } finally {
            server.unregister("/test/spill-cleanup", listOf(HttpMethod.POST))
        }
    }

    @Test
    fun `does not release an inbound provider while an engine registry owns it`() {
        streamingTempFiles(streamingTempDir).forEach { Files.deleteIfExists(it) }
        val registry = StreamingResourceRegistry()
        val providerRef = AtomicReference<MuseCursorStreamProvider>()
        val server = server()
        server.register("/test/managed-request", listOf(HttpMethod.POST)) { request ->
            val provider = request.bodyProvider ?: error("Expected bodyProvider")
            providerRef.set(provider)
            registry.register(provider)
            HttpResponse(statusCode = 202, body = "accepted".toByteArray())
        }

        try {
            val response =
                restTemplate.postForEntity(
                    "http://localhost:$port/test/managed-request",
                    "managed-payload",
                    String::class.java,
                )

            assertEquals(HttpStatus.ACCEPTED, response.statusCode)
            assertFalse(providerRef.get().isClosed)
            assertTrue(providerRef.get().isManaged)

            registry.releaseAll()
            assertTrue(providerRef.get().isClosed)
            assertEquals(emptyList<Path>(), streamingTempFiles(streamingTempDir))
        } finally {
            registry.releaseAll()
            server.unregister("/test/managed-request", listOf(HttpMethod.POST))
        }
    }

    @Test
    fun `does not release a managed inbound provider when the handler fails`() {
        streamingTempFiles(streamingTempDir).forEach { Files.deleteIfExists(it) }
        val registry = StreamingResourceRegistry()
        val providerRef = AtomicReference<MuseCursorStreamProvider>()
        val server = server()
        server.register("/test/managed-request-failure", listOf(HttpMethod.POST)) { request ->
            val provider = request.bodyProvider ?: error("Expected bodyProvider")
            providerRef.set(provider)
            registry.register(provider)
            error("handler failed")
        }

        try {
            val response =
                restTemplate.postForEntity(
                    "http://localhost:$port/test/managed-request-failure",
                    "managed-payload",
                    String::class.java,
                )

            assertFalse(response.statusCode.is2xxSuccessful)
            assertFalse(providerRef.get().isClosed)
            assertTrue(providerRef.get().isManaged)

            registry.releaseAll()
            assertTrue(providerRef.get().isClosed)
            assertEquals(emptyList<Path>(), streamingTempFiles(streamingTempDir))
        } finally {
            registry.releaseAll()
            server.unregister("/test/managed-request-failure", listOf(HttpMethod.POST))
        }
    }

    private fun streamingTempFiles(directory: Path): List<Path> =
        Files.list(directory).use { stream ->
            stream.filter { it.name.startsWith("muse-stream-") }.toList()
        }

    companion object {
        private val streamingTempDir: Path = Files.createTempDirectory("muse-spring-streaming-test")

        @JvmStatic
        @DynamicPropertySource
        fun streamingProperties(registry: DynamicPropertyRegistry) {
            registry.add("muse.streaming.file-store.in-memory-size-bytes") { "4" }
            registry.add("muse.streaming.file-store.temp-directory") { streamingTempDir.toString() }
            registry.add("muse.streaming.file-store.delete-files-on-close") { "true" }
        }
    }
}

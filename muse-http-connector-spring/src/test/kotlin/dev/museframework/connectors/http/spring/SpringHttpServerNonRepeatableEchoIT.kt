package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.BackgroundStreamPreparation
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.server.HttpServer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.name
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [HttpConnectorSpringTestApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestPropertySource(
    properties = [
        "muse.streaming.strategy=non-repeatable",
        "muse.streaming.file-store.in-memory-size-bytes=4",
        "muse.streaming.file-store.delete-files-on-close=true",
    ],
)
class SpringHttpServerNonRepeatableEchoIT {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var initializer: SpringHttpListenerServerInitializer

    @Test
    fun `detaches a large request backed response before writing it`() {
        streamingTempFiles().forEach { Files.deleteIfExists(it) }
        val server = server()

        server.register("/test/non-repeatable-echo", listOf(HttpMethod.POST)) { request ->
            HttpResponse(statusCode = 200, bodyProvider = request.bodyProvider)
        }

        try {
            val payload = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
            val httpRequest =
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$port/test/non-repeatable-echo"))
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build()

            val response = HttpClient.newHttpClient().send(httpRequest, BodyHandlers.ofByteArray())

            assertEquals(200, response.statusCode())
            assertContentEquals(payload, response.body())
            assertEquals(emptyList(), streamingTempFiles())
        } finally {
            server.unregister("/test/non-repeatable-echo", listOf(HttpMethod.POST))
        }
    }

    @Test
    fun `a failing superseded lease still releases the detached provider`() {
        streamingTempFiles().forEach { Files.deleteIfExists(it) }
        val supersededProvider = ThrowingReleaseProvider()
        val supersededLease = ownedClaim(supersededProvider)
        val server = server()

        server.register("/test/non-repeatable-doomed-lease", listOf(HttpMethod.POST)) { request ->
            HttpResponse(
                statusCode = 200,
                bodyProvider = request.bodyProvider,
                bodyLease = supersededLease,
            )
        }

        try {
            val payload = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
            val httpRequest =
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$port/test/non-repeatable-doomed-lease"))
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build()

            val response = HttpClient.newHttpClient().send(httpRequest, BodyHandlers.ofByteArray())

            assertTrue(
                response.statusCode() !in 200..299,
                "the superseded-claim failure propagates instead of writing a response",
            )
            assertEquals(
                emptyList(),
                streamingTempFiles(),
                "the detached provider's claim is released even when closing the superseded lease fails",
            )
            assertTrue(supersededProvider.isClosed, "the failing superseded claim still ended its lifecycle")
            assertEquals(1, supersededProvider.releases)
        } finally {
            server.unregister("/test/non-repeatable-doomed-lease", listOf(HttpMethod.POST))
        }
    }

    @Test
    fun `prepares a request bound body for background use before the request is released`() {
        streamingTempFiles().forEach { Files.deleteIfExists(it) }
        val providerRef = AtomicReference<MuseCursorStreamProvider>()
        val server = server()

        server.register("/test/non-repeatable-prepare", listOf(HttpMethod.POST)) { request ->
            val provider = checkNotNull(request.bodyProvider)
            assertIs<BackgroundStreamPreparation>(provider).prepareForBackgroundUse()
            providerRef.set(provider)
            HttpResponse(
                statusCode = 200,
                body = provider.openCursor().use { it.readBytes() },
            )
        }

        try {
            val payload = "request-bound-background-payload"
            val response = post("/test/non-repeatable-prepare", payload.toByteArray())

            assertEquals(200, response.statusCode())
            assertContentEquals(payload.toByteArray(), response.body())
            assertTrue(providerRef.get().isClosed, "The HTTP adapter must release the prepared inbound provider")
            assertEquals(emptyList(), streamingTempFiles())
        } finally {
            server.unregister("/test/non-repeatable-prepare", listOf(HttpMethod.POST))
        }
    }

    @Test
    fun `prepares a request bound body that is still echoed as the exact response`() {
        streamingTempFiles().forEach { Files.deleteIfExists(it) }
        val server = server()

        server.register("/test/non-repeatable-prepared-echo", listOf(HttpMethod.POST)) { request ->
            val provider = checkNotNull(request.bodyProvider)
            assertIs<BackgroundStreamPreparation>(provider).prepareForBackgroundUse()
            HttpResponse(statusCode = 200, bodyProvider = request.bodyProvider)
        }

        try {
            val payload = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
            val response = post("/test/non-repeatable-prepared-echo", payload)

            assertEquals(200, response.statusCode())
            assertContentEquals(payload, response.body())
            assertEquals(emptyList(), streamingTempFiles())
        } finally {
            server.unregister("/test/non-repeatable-prepared-echo", listOf(HttpMethod.POST))
        }
    }

    private fun server(): HttpServer =
        initializer.initialize(
            HttpListenerConnectionConfig(
                name = "non-repeatable-echo-it",
                host = "localhost",
                port = port,
            ),
        )

    private fun post(
        path: String,
        payload: ByteArray,
    ): java.net.http.HttpResponse<ByteArray> {
        val httpRequest =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build()
        return HttpClient.newHttpClient().send(httpRequest, BodyHandlers.ofByteArray())
    }

    private fun streamingTempFiles(): List<Path> =
        Files.list(streamingTempDirectory).use { paths ->
            paths.filter { it.name.startsWith("muse-stream-") }.toList()
        }

    companion object {
        private val streamingTempDirectory: Path =
            Files.createTempDirectory("muse-non-repeatable-echo-it")

        @JvmStatic
        @DynamicPropertySource
        fun streamingProperties(registry: DynamicPropertyRegistry) {
            registry.add("muse.streaming.file-store.temp-directory") {
                streamingTempDirectory.toString()
            }
        }
    }
}

/** A claim whose release always fails, so cleanup has to attach the failure and keep going. */
private class ThrowingReleaseProvider : MuseCursorStreamProvider {
    override var isClosed: Boolean = false
        private set
    var releases: Int = 0
        private set

    override val size: Long = -1

    override fun openCursor(): dev.museframework.engine.api.streaming.MuseCursorStream =
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

private fun ownedClaim(provider: MuseCursorStreamProvider) =
    StreamingResourceRegistry.ownedClaim(provider)

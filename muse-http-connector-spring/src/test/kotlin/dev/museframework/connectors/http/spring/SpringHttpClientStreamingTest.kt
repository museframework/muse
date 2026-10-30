package dev.museframework.connectors.http.spring

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingStrategy
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.http.api.processor.HttpOutboundRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.lang.ref.WeakReference
import kotlin.test.assertFailsWith

class SpringHttpClientStreamingTest {
    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    @Test
    fun `client exposes response body as repeatable streaming provider`() = runBlocking {
        val payload = "streaming-client-response".repeat(128)
        wireMock.stubFor(
            get(urlPathEqualTo("/stream-response"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(payload),
                ),
        )
        val client = SpringHttpClient(
            config = HttpRequestConnectionConfig(
                host = "localhost",
                port = wireMock.port,
                protocol = HttpProtocol.HTTP,
            ),
            streamingStrategy = StreamingStrategy.RepeatableFileStore(inMemorySizeBytes = 4),
        )

        val response = client.execute(
            HttpOutboundRequest(
                method = HttpMethod.GET,
                url = "http://localhost:${wireMock.port}/stream-response",
            ),
        )

        assertEquals(200, response.statusCode)
        assertNotNull(response.bodyProvider)
        assertEquals(payload, response.bodyProvider!!.openCursor().use { it.readBytes().decodeToString() })
        assertEquals(payload, response.bodyProvider!!.openCursor().use { it.readBytes().decodeToString() })
        response.bodyProvider!!.releaseResources()
    }

    @Test
    fun `client sends body from bodyProvider on outbound request`() = runBlocking {
        val payload = "outbound-bodyProvider-test".repeat(64)
        wireMock.stubFor(
            post(urlPathEqualTo("/body-provider-outbound"))
                .withHeader("Content-Type", equalTo("text/plain"))
                .withRequestBody(equalTo(payload))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("ok"),
                ),
        )
        val client = SpringHttpClient(
            config = HttpRequestConnectionConfig(
                host = "localhost",
                port = wireMock.port,
                protocol = HttpProtocol.HTTP,
            ),
            streamingStrategy = StreamingStrategy.RepeatableFileStore(inMemorySizeBytes = 4),
        )

        val bodyProvider = RepeatableInMemoryCursorStreamProvider.fromBytes(payload.toByteArray())
        val response = client.execute(
            HttpOutboundRequest(
                method = HttpMethod.POST,
                url = "http://localhost:${wireMock.port}/body-provider-outbound",
                bodyProvider = bodyProvider,
                contentType = "text/plain",
            ),
        )

        assertEquals(200, response.statusCode)
        assertEquals("ok", response.bodyProvider!!.openCursor().use { it.readBytes().decodeToString() })
        response.bodyProvider!!.releaseResources()
        bodyProvider.releaseResources()
    }

    @Test
    fun `non-repeatable response stays open after the RestClient exchange callback`() = runBlocking {
        val payload = "live-response-stream".repeat(64)
        wireMock.stubFor(
            get(urlPathEqualTo("/non-repeatable-response"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(payload),
                ),
        )
        val client =
            SpringHttpClient(
                config =
                    HttpRequestConnectionConfig(
                        host = "localhost",
                        port = wireMock.port,
                        protocol = HttpProtocol.HTTP,
                    ),
                streamingStrategy = StreamingStrategy.NonRepeatable,
            )

        val response =
            client.execute(
                HttpOutboundRequest(
                    method = HttpMethod.GET,
                    url = "http://localhost:${wireMock.port}/non-repeatable-response",
                ),
            )

        val provider = checkNotNull(response.bodyProvider)
        assertEquals(payload, provider.openCursor().use { it.readBytes().decodeToString() })
        assertFailsWith<IllegalStateException> { provider.openCursor() }
        provider.releaseResources()
    }

    @Test
    fun `client omits a provider for responses without a body`() = runBlocking {
        wireMock.stubFor(
            get(urlPathEqualTo("/no-content"))
                .willReturn(aResponse().withStatus(204)),
        )
        val client =
            SpringHttpClient(
                config =
                    HttpRequestConnectionConfig(
                        host = "localhost",
                        port = wireMock.port,
                        protocol = HttpProtocol.HTTP,
                    ),
                streamingStrategy = StreamingStrategy.NonRepeatable,
            )

        val response =
            client.execute(
                HttpOutboundRequest(
                    method = HttpMethod.GET,
                    url = "http://localhost:${wireMock.port}/no-content",
                ),
            )

        assertEquals(204, response.statusCode)
        assertNull(response.bodyProvider)
    }

    @ParameterizedTest
    @ValueSource(strings = ["bulk-eof", "single-byte-eof", "seek-eof", "invalid-seek", "close", "release"])
    fun `completed response frees connection while its provider is still registered`(completion: String) = runBlocking {
        wireMock.stubFor(get(urlPathEqualTo("/bounded-response")).willReturn(aResponse().withBody("payload")))
        val client = SpringHttpClient(
            HttpRequestConnectionConfig(host = "localhost", port = wireMock.port, maxConnections = 1),
            StreamingStrategy.NonRepeatable,
        )
        val request = HttpOutboundRequest(method = HttpMethod.GET, url = "http://localhost:${wireMock.port}/bounded-response")
        val resources = StreamingResourceRegistry()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = resources.register(checkNotNull(client.execute(request).bodyProvider))
            val cursor = first.openCursor()
            try {
                when (completion) {
                    "bulk-eof" -> assertEquals("payload", cursor.readBytes().decodeToString())
                    "single-byte-eof" -> while (cursor.read() != -1) Unit
                    "seek-eof" -> cursor.seek(100)
                    "invalid-seek" -> {
                        assertEquals('p'.code, cursor.read())
                        assertFailsWith<IllegalArgumentException> { cursor.seek(0) }
                        assertEquals("ayload", cursor.readBytes().decodeToString())
                    }
                    "close" -> { cursor.read(); cursor.close() }
                    "release" -> cursor.release()
                }
                assertTrue(first.isManaged)
                assertFalse(first.isClosed)
                val next = executor.submit<Int> {
                    runBlocking {
                        val response = client.execute(request)
                        val provider = resources.register(checkNotNull(response.bodyProvider))
                        assertEquals("payload", provider.openCursor().use { it.readBytes().decodeToString() })
                        response.statusCode
                    }
                }
                try {
                    assertEquals(200, next.get(2, TimeUnit.SECONDS))
                } finally {
                    next.cancel(true)
                }
            } finally {
                cursor.close()
            }
        } finally {
            resources.releaseAll()
            executor.shutdownNow()
            client.close()
        }
    }

    @Test
    fun `abandoned unread response releases connection capacity while its registry stays active`() {
        wireMock.stubFor(get(urlPathEqualTo("/abandoned-response")).willReturn(aResponse().withBody("payload")))
        val client = SpringHttpClient(
            HttpRequestConnectionConfig(host = "localhost", port = wireMock.port, maxConnections = 1),
            StreamingStrategy.NonRepeatable,
        )
        val request = HttpOutboundRequest(method = HttpMethod.GET, url = "http://localhost:${wireMock.port}/abandoned-response")
        val resources = StreamingResourceRegistry()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val abandoned = abandonedResponse(client, request, resources)
            val next = executor.submit<Int> {
                runBlocking {
                    val response = client.execute(request)
                    resources.register(checkNotNull(response.bodyProvider)).openCursor().use { cursor ->
                        assertEquals("payload", cursor.readBytes().decodeToString())
                    }
                    response.statusCode
                }
            }
            try {
                assertFailsWith<TimeoutException> { next.get(100, TimeUnit.MILLISECONDS) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (abandoned.get() != null && System.nanoTime() < deadline) {
                    System.gc()
                    Thread.sleep(20)
                }
                assertNull(abandoned.get(), "An active registry must not retain an abandoned response")
                assertEquals(200, next.get(5, TimeUnit.SECONDS), "Cleanup must release the outer connection permit")
            } finally {
                next.cancel(true)
            }
        } finally {
            resources.releaseAll()
            executor.shutdownNow()
            client.close()
        }
    }

    private fun abandonedResponse(
        client: SpringHttpClient,
        request: HttpOutboundRequest,
        resources: StreamingResourceRegistry,
    ): WeakReference<MuseCursorStreamProvider> = runBlocking {
        WeakReference(resources.register(checkNotNull(client.execute(request).bodyProvider)))
    }

    @Test
    fun `registry cleanup does not return a consumed connection permit twice`() = runBlocking {
        wireMock.stubFor(get(urlPathEqualTo("/single-permit")).willReturn(aResponse().withBody("payload")))
        val client = SpringHttpClient(
            HttpRequestConnectionConfig(host = "localhost", port = wireMock.port, maxConnections = 1),
            StreamingStrategy.NonRepeatable,
        )
        val request = HttpOutboundRequest(method = HttpMethod.GET, url = "http://localhost:${wireMock.port}/single-permit")
        val firstResources = StreamingResourceRegistry()
        val nextResources = StreamingResourceRegistry()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = firstResources.register(checkNotNull(client.execute(request).bodyProvider))
            first.openCursor().use { assertEquals("payload", it.readBytes().decodeToString()) }
            firstResources.releaseAll()

            val second = nextResources.register(checkNotNull(client.execute(request).bodyProvider))
            val third = executor.submit<Int> {
                runBlocking {
                    val response = client.execute(request)
                    nextResources.register(checkNotNull(response.bodyProvider))
                    response.statusCode
                }
            }
            try {
                assertFailsWith<TimeoutException> { third.get(200, TimeUnit.MILLISECONDS) }
                second.openCursor().use { assertEquals("payload", it.readBytes().decodeToString()) }
                assertEquals(200, third.get(2, TimeUnit.SECONDS))
            } finally {
                third.cancel(true)
            }
        } finally {
            firstResources.releaseAll()
            nextResources.releaseAll()
            executor.shutdownNow()
            client.close()
        }
    }

    @Test
    fun `query parameters preserve an already encoded URI path`() = runBlocking {
        wireMock.stubFor(
            get(urlEqualTo("/items/A%20B%2FC%252F?filter=two%20words"))
                .willReturn(aResponse().withStatus(204)),
        )
        val client = SpringHttpClient(HttpRequestConnectionConfig(host = "localhost", port = wireMock.port))
        try {
            val response = client.execute(
                HttpOutboundRequest(
                    method = HttpMethod.GET,
                    url = "http://localhost:${wireMock.port}/items/A%20B%2FC%252F",
                    queryParams = mapOf("filter" to "two words"),
                ),
            )
            try {
                assertEquals(204, response.statusCode)
            } finally {
                response.bodyProvider?.releaseResources()
            }
        } finally {
            client.close()
        }
    }
}

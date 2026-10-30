package dev.museframework.connectors.http.spring

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.processor.HttpOutboundRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlin.test.assertFailsWith

/** Exercises Spring-specific transport behavior beyond the reusable client contract. */
class SpringHttpClientWireMockIT {
    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension =
            WireMockExtension
                .newInstance()
                .options(wireMockConfig().dynamicPort())
                .build()
    }

    private lateinit var httpClient: SpringHttpClient

    @BeforeEach
    fun setup() {
        httpClient = client(port = wireMock.port)
    }

    @AfterEach
    fun teardown() {
        httpClient.close()
        wireMock.resetMappings()
    }

    private fun client(
        port: Int,
        protocol: HttpProtocol = HttpProtocol.HTTP,
        responseTimeoutMs: Long = 5_000,
    ) =
        SpringHttpClient(
            HttpRequestConnectionConfig(
                name = "wiremock",
                host = "localhost",
                port = port,
                protocol = protocol,
                connectionIdleTimeoutMs = 1_000,
                responseTimeoutMs = responseTimeoutMs,
            ),
        )

    private inline fun <reified T : Throwable> assertCauseChainContains(exception: Throwable) {
        assertTrue(
            generateSequence(exception) { it.cause }.any { it is T },
            "Expected ${T::class.simpleName} in cause chain but got $exception",
        )
    }

    @Test
    fun `should throw timeout exception when response exceeds timeout`() =
        runBlocking {
            stubTimeoutResponse(2_000)

            val exception =
                assertFailsWith<ResourceAccessException> {
                    httpClient.execute(
                        HttpOutboundRequest(
                            method = HttpMethod.GET,
                            url = "http://localhost:${wireMock.port}/test",
                            responseTimeoutMs = 500,
                        ),
                    )
                }

            assertCauseChainContains<SocketTimeoutException>(exception)
        }

    @Test
    fun `should succeed when response is within timeout`() =
        runBlocking {
            stubTimeoutResponse(100)

            val response =
                httpClient.execute(
                    HttpOutboundRequest(
                        method = HttpMethod.GET,
                        url = "http://localhost:${wireMock.port}/test",
                        responseTimeoutMs = 5_000,
                    ),
                )

            assertEquals(200, response.statusCode)
        }

    @Test
    fun `should throw exception when connection is reset`() =
        runBlocking {
            stubConnectionFailure(Fault.CONNECTION_RESET_BY_PEER)

            val exception =
                assertFailsWith<ResourceAccessException> {
                    httpClient.execute(request("/test"))
                }

            assertCauseChainContains<IOException>(exception)
        }

    @Test
    fun `should throw exception when response is malformed`() =
        runBlocking {
            stubConnectionFailure(Fault.MALFORMED_RESPONSE_CHUNK)

            val exception =
                assertFailsWith<RestClientException> {
                    httpClient.execute(request("/test"))
                }

            assertCauseChainContains<IOException>(exception)
        }

    @Test
    fun `should follow multiple redirects`() =
        runBlocking {
            wireMock.stubFor(
                get(urlPathEqualTo("/redirect1"))
                    .willReturn(aResponse().withStatus(301).withHeader("Location", "/redirect2")),
            )
            wireMock.stubFor(
                get(urlPathEqualTo("/redirect2"))
                    .willReturn(aResponse().withStatus(302).withHeader("Location", "/final")),
            )
            wireMock.stubFor(
                get(urlPathEqualTo("/final"))
                    .willReturn(aResponse().withStatus(200).withBody("final")),
            )

            val response = httpClient.execute(request("/redirect1", followRedirects = true))

            assertEquals(200, response.statusCode)
            assertEquals("final", response.bodyBytes()?.decodeToString())
        }

    @Test
    fun `should handle response body larger than 1MB`() =
        runBlocking {
            val largeBody = "x".repeat(1_500_000)
            wireMock.stubFor(
                get(urlPathEqualTo("/large"))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody(largeBody)),
            )

            val response = httpClient.execute(request("/large"))

            assertEquals(200, response.statusCode)
            assertEquals(1_500_000, response.bodyBytes()?.size)
        }

    @Test
    fun `should handle response body larger than 10MB`() =
        runBlocking {
            val largeBody = "x".repeat(11_000_000)
            wireMock.stubFor(
                get(urlPathEqualTo("/huge"))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody(largeBody)),
            )

            val response = httpClient.execute(request("/huge"))

            assertEquals(200, response.statusCode)
            assertEquals(11_000_000, response.bodyBytes()?.size)
        }

    @Test
    fun `should fail HTTPS request with self-signed certificate by default`() =
        runBlocking {
            val httpsWireMock = WireMockServer(wireMockConfig().dynamicHttpsPort().httpDisabled(true))
            httpsWireMock.start()

            try {
                httpsWireMock.stubFor(
                    get(urlPathEqualTo("/secure"))
                        .willReturn(aResponse().withStatus(200).withBody("secure data")),
                )
                val httpsClient = client(port = httpsWireMock.httpsPort(), protocol = HttpProtocol.HTTPS)

                try {
                    val exception =
                        assertFailsWith<ResourceAccessException> {
                            httpsClient.execute(
                                HttpOutboundRequest(
                                    method = HttpMethod.GET,
                                    url = "https://localhost:${httpsWireMock.httpsPort()}/secure",
                                ),
                            )
                        }

                    assertCauseChainContains<SSLException>(exception)
                } finally {
                    httpsClient.close()
                }
            } finally {
                httpsWireMock.stop()
            }
        }

    @Test
    fun `should handle multiple concurrent requests to same endpoint`() =
        runBlocking {
            wireMock.stubFor(
                get(urlPathEqualTo("/concurrent"))
                    .willReturn(aResponse().withStatus(200).withBody("response")),
            )
            val request = request("/concurrent")

            val responses =
                (1..10)
                    .map { async(Dispatchers.IO) { httpClient.execute(request) } }
                    .awaitAll()

            assertEquals(10, responses.size)
            responses.forEach { response -> assertEquals(200, response.statusCode) }
            wireMock.verify(10, getRequestedFor(urlPathEqualTo("/concurrent")))
        }

    private fun request(
        path: String,
        followRedirects: Boolean = true,
    ): HttpOutboundRequest =
        HttpOutboundRequest(
            method = HttpMethod.GET,
            url = "http://localhost:${wireMock.port}$path",
            followRedirects = followRedirects,
        )

    private fun HttpResponse.bodyBytes(): ByteArray? =
        bodyProvider?.openCursor()?.use { it.readBytes() } ?: body

    private fun stubTimeoutResponse(delayMs: Int) {
        wireMock.stubFor(
            get(urlPathEqualTo("/test"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(delayMs)),
        )
    }

    private fun stubConnectionFailure(fault: Fault) {
        wireMock.stubFor(
            get(urlPathEqualTo("/test"))
                .willReturn(aResponse().withFault(fault)),
        )
    }
}

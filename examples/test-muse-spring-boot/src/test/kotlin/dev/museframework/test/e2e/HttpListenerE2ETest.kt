package dev.museframework.test.e2e

import dev.museframework.test.Application
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.test.annotation.DirtiesContext
import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.client.RestTemplate
import java.net.URI

@SpringBootTest(
    classes = [Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("HTTP Listener E2E Tests")
class HttpListenerE2ETest {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var restTemplate: RestTemplate

    @BeforeEach
    fun setUp() {
        restTemplate =
            RestTemplate().apply {
                errorHandler =
                    object : ResponseErrorHandler {
                        override fun hasError(response: ClientHttpResponse): Boolean = false

                        override fun handleError(
                            url: URI,
                            method: HttpMethod,
                            response: ClientHttpResponse,
                        ) {}
                    }
            }
    }

    private fun baseUrl(): String = "http://localhost:$port"

    @Nested
    @DirtiesContext
    @DisplayName("GET /api/hello")
    inner class GetHello {
        @Test
        fun `should return 200 OK with hello world payload`() {
            val response =
                restTemplate.getForEntity(
                    "${baseUrl()}/api/hello",
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertNotNull(response.body)
            assertTrue(
                response.body!!.contains("hello world"),
                "Response body should contain 'hello world', got: ${response.body}",
            )
        }

        @Test
        fun `should return response with content type`() {
            val response =
                restTemplate.getForEntity(
                    "${baseUrl()}/api/hello",
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertNotNull(response.headers.contentType)
        }
    }

    @Nested
    @DirtiesContext
    @DisplayName("POST /api/hello")
    inner class PostHello {
        @Test
        fun `should accept POST request with JSON body and return 200`() {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            val requestBody = """{"message": "test input"}"""
            val request = HttpEntity(requestBody, headers)

            val response =
                restTemplate.postForEntity(
                    "${baseUrl()}/api/hello",
                    request,
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertNotNull(response.body)
            assertTrue(
                response.body!!.contains("hello world"),
                "Response body should contain 'hello world', got: ${response.body}",
            )
        }

        @Test
        fun `should accept POST request with plain text body`() {
            val headers = HttpHeaders()
            headers.contentType = MediaType.TEXT_PLAIN
            val request = HttpEntity("plain text body", headers)

            val response =
                restTemplate.postForEntity(
                    "${baseUrl()}/api/hello",
                    request,
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertNotNull(response.body)
        }
    }

    @Nested
    @DirtiesContext
    @DisplayName("Custom Headers")
    inner class CustomHeaders {
        @Test
        fun `should accept request with X-Correlation-ID header`() {
            val headers = HttpHeaders()
            headers.set("X-Correlation-ID", "test-correlation-123")
            val request = HttpEntity<String>(null, headers)
            val response =
                restTemplate.exchange(
                    "${baseUrl()}/api/hello",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
            assertNotNull(response.body)
        }

        @Test
        fun `should accept request with multiple custom headers`() {
            val headers = HttpHeaders()
            headers.set("X-Correlation-ID", "corr-456")
            headers.set("X-Request-ID", "req-789")
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE)
            val request = HttpEntity<String>(null, headers)

            val response =
                restTemplate.exchange(
                    "${baseUrl()}/api/hello",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, response.statusCode)
        }
    }

    @Nested
    @DirtiesContext
    @DisplayName("Error Scenarios")
    inner class ErrorScenarios {
        @Test
        fun `should return 404 for unknown path`() {
            val response =
                restTemplate.getForEntity(
                    "${baseUrl()}/api/nonexistent",
                    String::class.java,
                )

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        }

        @Test
        fun `should return 404 for path outside api prefix`() {
            val response =
                restTemplate.getForEntity(
                    "${baseUrl()}/unknown/path",
                    String::class.java,
                )

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        }

        @Test
        fun `should return 404 for root path`() {
            val response =
                restTemplate.getForEntity(
                    "${baseUrl()}/",
                    String::class.java,
                )

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        }
    }
}

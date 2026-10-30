package dev.museframework.connectors.http.spring.fuzz

import dev.museframework.connectors.http.spring.HttpConnectorSpringTestApp
import io.kotest.property.RandomSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

@SpringBootTest(
    classes = [HttpConnectorSpringTestApp::class, FuzzTestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("HTTP Listener Fuzz Tests")
class HttpListenerFuzzTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private lateinit var baseUrl: String

    @BeforeEach
    fun setUp() {
        baseUrl = "http://localhost:$port/api/fuzz"
    }

    @Test
    @DisplayName("should reject malformed JSON through the application contract")
    fun `should reject malformed JSON through the application contract`() {
        val iterations = 100
        val failures = mutableListOf<String>()

        val generator = FuzzGenerators.jsonMalformed(seed = 42L)
        val randomSource = RandomSource.seeded(42L)

        repeat(iterations) {
            val malformedJson = generator.sample(randomSource).value

            try {
                val headers = HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                }
                val entity = HttpEntity(malformedJson, headers)

                val response = restTemplate.exchange(
                    baseUrl,
                    HttpMethod.POST,
                    entity,
                    String::class.java
                )

                if (response.statusCode != HttpStatus.BAD_REQUEST || response.body != "APP:INVALID_REQUEST") {
                    failures +=
                        "input=${malformedJson.take(100)}, status=${response.statusCode}, " +
                            "body=${response.body?.take(200)}"
                }
            } catch (e: Exception) {
                failures += "input=${malformedJson.take(100)}, exception=${e.javaClass.simpleName}: ${e.message}"
            }
        }

        println("[FUZZ] Iterations: $iterations")
        println("[FUZZ] Invalid responses: ${failures.size}")

        assertTrue(failures.isEmpty()) {
            "Malformed JSON did not use the explicit APP:INVALID_REQUEST contract:\n" +
                failures.take(10).joinToString("\n")
        }
    }

    @Test
    @DisplayName("should reject JSON roots that are not objects")
    fun `should reject JSON roots that are not objects`() {
        val nonObjectRoots = listOf("[]", "\"value\"", "42", "true", "null")
        val failures = mutableListOf<String>()

        nonObjectRoots.forEach { json ->
            val headers =
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                }
            val response =
                restTemplate.exchange(
                    baseUrl,
                    HttpMethod.POST,
                    HttpEntity(json, headers),
                    String::class.java,
                )

            if (response.statusCode != HttpStatus.BAD_REQUEST || response.body != "APP:INVALID_REQUEST") {
                failures += "input=$json, status=${response.statusCode}, body=${response.body?.take(200)}"
            }
        }

        assertTrue(failures.isEmpty()) {
            "Non-object JSON did not use the explicit APP:INVALID_REQUEST contract:\n" +
                failures.joinToString("\n")
        }
    }

    @Test
    @DisplayName("should process a valid JSON object")
    fun `should process a valid JSON object`() {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
            }
        val entity = HttpEntity("""{"field":"expected-value","count":1}""", headers)

        val response =
            restTemplate.exchange(
                baseUrl,
                HttpMethod.POST,
                entity,
                String::class.java,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body?.contains("expected-value") == true) {
            "Valid response did not contain the extracted field: ${response.body}"
        }
    }

    @Test
    @DisplayName("should handle malformed paths")
    fun `should handle malformed paths`() {
        val iterations = 100
        var notFoundCount = 0
        var badRequestCount = 0
        val failures = mutableListOf<String>()

        val generator = FuzzGenerators.pathTraversal(seed = 42L)
        val randomSource = RandomSource.seeded(42L)

        // Additional unicode paths to test
        val unicodePaths = listOf(
            "/api/füzz",
            "/api/fuzz🔥",
            "/api/日本語",
            "/api/🚀🎉💥",
            "/api/%c0%ae%c0%ae",  // Overlong UTF-8 encoding
            "/api/..%c0%af",      // Overlong encoding of /
        )

        repeat(iterations) { i ->
            val malformedPath = if (i < unicodePaths.size) {
                unicodePaths[i]
            } else {
                generator.sample(randomSource).value
            }

            try {
                val response = restTemplate.exchange(
                    "$baseUrl$malformedPath",
                    HttpMethod.POST,
                    HttpEntity.EMPTY,
                    String::class.java
                )

                val status = response.statusCode

                when (status) {
                    HttpStatus.NOT_FOUND -> notFoundCount++
                    HttpStatus.BAD_REQUEST -> badRequestCount++
                    else ->
                        failures +=
                            "path=${malformedPath.take(160)}, status=$status, body=${response.body?.take(160)}"
                }
            } catch (e: Exception) {
                failures +=
                    "path=${malformedPath.take(160)}, exception=${e.javaClass.simpleName}: ${e.message}"
            }
        }

        println("[FUZZ-PATH] Iterations: $iterations")
        println("[FUZZ-PATH] 404 responses: $notFoundCount")
        println("[FUZZ-PATH] 400 responses: $badRequestCount")
        println("[FUZZ-PATH] Invalid outcomes: ${failures.size}")

        assertTrue(failures.isEmpty()) {
            "Path fuzzing produced outcomes other than HTTP 400 or 404:\n" +
                failures.take(10).joinToString("\n")
        }
    }

    @Test
    @DisplayName("should process supported large JSON objects")
    fun `should process supported large JSON objects`() {
        val sizesToTest = listOf(
            1024 * 1024,
            2 * 1024 * 1024,
        )
        val failures = mutableListOf<String>()

        val randomSource = RandomSource.seeded(42L)

        sizesToTest.forEach { targetSize ->
            val payload =
                FuzzGenerators
                    .largeJsonObjectPayload(size = targetSize, seed = 42L + targetSize)
                    .sample(randomSource)
                    .value
            assertTrue(payload.length == targetSize) {
                "Generator returned ${payload.length} characters instead of $targetSize"
            }

            try {
                val headers = HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                }
                val entity = HttpEntity(payload, headers)

                val startTime = System.currentTimeMillis()
                val response = restTemplate.exchange(
                    baseUrl,
                    HttpMethod.POST,
                    entity,
                    String::class.java
                )
                val elapsed = System.currentTimeMillis() - startTime

                val status = response.statusCode
                println("[FUZZ-SIZE] ${targetSize / 1024 / 1024}MB: Got status $status in ${elapsed}ms")
                if (status != HttpStatus.OK) {
                    failures +=
                        "size=$targetSize, status=$status, body=${response.body?.take(160)}"
                }

                if (elapsed >= 60000) {
                    failures += "size=$targetSize, elapsedMs=$elapsed"
                }
            } catch (e: OutOfMemoryError) {
                throw AssertionError("Server ran out of memory for ${targetSize / 1024 / 1024}MB payload", e)
            } catch (e: Exception) {
                failures += "size=$targetSize, exception=${e.javaClass.simpleName}: ${e.message}"
            }
        }

        println("[FUZZ-SIZE] Total tests: ${sizesToTest.size}")
        println("[FUZZ-SIZE] Invalid outcomes: ${failures.size}")

        assertTrue(failures.isEmpty()) {
            "Large JSON robustness contract failed:\n" + failures.joinToString("\n")
        }
    }
}

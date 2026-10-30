package dev.museframework.connectors.http.spring

import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest(
    classes = [HttpConnectorSpringTestApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureTestRestTemplate
@TestPropertySource(
    properties = [
        "muse.streaming.strategy=repeatable-in-memory",
        "muse.streaming.in-memory.initial-buffer-size-bytes=4",
        "muse.streaming.in-memory.buffer-size-increment-bytes=4",
        "muse.streaming.in-memory.max-buffer-size-bytes=8",
    ],
)
class SpringHttpServerMaximumSizeIT {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var initializer: SpringHttpListenerServerInitializer

    @Test
    fun `returns payload too large instead of swallowing an inbound stream limit failure`() {
        val invoked = AtomicBoolean(false)
        val server =
            initializer.initialize(
                HttpListenerConnectionConfig(
                    name = "maximum-size-it",
                    host = "localhost",
                    port = port,
                ),
            )
        server.register("/test/maximum-size", listOf(HttpMethod.POST)) {
            invoked.set(true)
            HttpResponse(statusCode = 200)
        }

        try {
            val response =
                restTemplate.postForEntity(
                    "http://localhost:$port/test/maximum-size",
                    "body-larger-than-eight-bytes",
                    String::class.java,
                )

            assertEquals(413, response.statusCode.value())
            assertContains(response.body.orEmpty(), "configured streaming limit of 8 bytes")
            assertFalse(invoked.get(), "The handler must not receive a partially materialized request")
        } finally {
            server.unregister("/test/maximum-size", listOf(HttpMethod.POST))
        }
    }
}

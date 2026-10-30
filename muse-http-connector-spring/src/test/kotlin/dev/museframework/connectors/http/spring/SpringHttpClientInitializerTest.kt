package dev.museframework.connectors.http.spring

import dev.museframework.http.api.config.HttpRequestConnectionConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SpringHttpClientInitializerTest {
    @Test
    fun `auto-configuration should create the production HTTP client`() {
        val initializer =
            MuseHttpAutoConfiguration().springHttpClientInitializer(MuseHttpStreamingProperties())

        val client = initializer.initialize(connectionConfig())

        assertIs<SpringHttpClient>(client)
    }

    @Test
    fun `initializer should reuse client for the same connection configuration`() {
        val initializer = SpringHttpClientInitializer()
        val config = connectionConfig()

        val first = initializer.initialize(config)
        val second = initializer.initialize(config)

        assertSame(first, second)
    }

    @Test
    fun `initializer should not reuse client when response timeout differs`() {
        val initializer = SpringHttpClientInitializer()

        val first = initializer.initialize(connectionConfig(responseTimeoutMs = 1_000))
        val second = initializer.initialize(connectionConfig(responseTimeoutMs = 2_000))

        assertNotSame(first, second)
    }

    private fun connectionConfig(responseTimeoutMs: Long = 5_000) =
        HttpRequestConnectionConfig(
            name = "wiremock",
            host = "localhost",
            port = 8080,
            responseTimeoutMs = responseTimeoutMs,
        )
}

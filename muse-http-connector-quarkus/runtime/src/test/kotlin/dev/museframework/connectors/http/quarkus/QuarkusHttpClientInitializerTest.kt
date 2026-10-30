package dev.museframework.connectors.http.quarkus

import dev.museframework.http.api.config.HttpRequestConnectionConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class QuarkusHttpClientInitializerTest {
    private var vertx: Vertx? = null
    private var initializer: QuarkusHttpClientInitializer? = null

    @AfterEach
    fun closeResources() {
        initializer?.close()
        vertx?.close()?.await()
    }

    @Test
    fun `initializer reuses only semantically equivalent connection configurations`() {
        val testedInitializer = createInitializer()
        val base = connectionConfig()

        val first = testedInitializer.initialize(base)
        val equivalent = testedInitializer.initialize(base.copy(name = "another-name", docId = "another-doc-id"))

        assertSame(first, equivalent)
        listOf(
            base.copy(responseTimeoutMs = 2_000),
            base.copy(connectionIdleTimeoutMs = 3_000),
            base.copy(usePersistentConnections = false),
            base.copy(maxConnections = 7),
            base.copy(enableCookies = false),
            base.copy(connectionTimeoutMs = 5_000),
        ).forEach { different ->
            assertNotSame(first, testedInitializer.initialize(different))
        }
    }

    @Test
    fun `connection settings map to Vertx client options`() {
        val options =
            QuarkusWebClientHttpClient.webClientOptions(
                connectionConfig(
                    connectionTimeoutMs = 2_000,
                    connectionIdleTimeoutMs = 1_234,
                    usePersistentConnections = false,
                    maxConnections = 7,
                ),
            )

        assertEquals(2_000, options.connectTimeout)
        assertEquals(1_234, options.idleTimeout)
        assertEquals(TimeUnit.MILLISECONDS, options.idleTimeoutUnit)
        assertFalse(options.isKeepAlive)
        assertEquals(7, options.maxPoolSize)
        assertFalse(options.isFollowRedirects)
    }

    private fun createInitializer(): QuarkusHttpClientInitializer {
        val testedVertx = Vertx.vertx()
        vertx = testedVertx
        return QuarkusHttpClientInitializer(testedVertx).also { initializer = it }
    }

    private fun connectionConfig(
        connectionTimeoutMs: Long = 1_000,
        connectionIdleTimeoutMs: Long = 1_000,
        responseTimeoutMs: Long = 1_000,
        usePersistentConnections: Boolean = true,
        maxConnections: Int = -1,
    ): HttpRequestConnectionConfig =
        HttpRequestConnectionConfig(
            name = "client",
            host = "127.0.0.1",
            port = 8080,
            connectionTimeoutMs = connectionTimeoutMs,
            connectionIdleTimeoutMs = connectionIdleTimeoutMs,
            responseTimeoutMs = responseTimeoutMs,
            usePersistentConnections = usePersistentConnections,
            maxConnections = maxConnections,
        )

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 10L
    }
}

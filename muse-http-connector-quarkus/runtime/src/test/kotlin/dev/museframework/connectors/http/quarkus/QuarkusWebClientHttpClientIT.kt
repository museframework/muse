package dev.museframework.connectors.http.quarkus

import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.testkit.HttpClientContract
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuarkusWebClientHttpClientIT : HttpClientContract() {
    private lateinit var vertx: Vertx

    @BeforeAll
    fun createVertx() {
        vertx = Vertx.vertx()
    }

    @AfterAll
    fun closeVertx() {
        if (::vertx.isInitialized) {
            vertx.close().await()
        }
    }

    override fun createClient(config: HttpRequestConnectionConfig): HttpClient =
        QuarkusWebClientHttpClient(vertx, config)

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 10L
    }
}

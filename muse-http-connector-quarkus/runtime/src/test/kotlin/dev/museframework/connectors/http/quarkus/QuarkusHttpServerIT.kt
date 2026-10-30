package dev.museframework.connectors.http.quarkus

import dev.museframework.http.api.server.HttpServer
import dev.museframework.http.testkit.HttpServerContract
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Router
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuarkusHttpServerIT : HttpServerContract() {
    private lateinit var vertx: Vertx
    private lateinit var transport: io.vertx.core.http.HttpServer
    private lateinit var adapter: QuarkusHttpServer
    private lateinit var endpoint: URI

    @BeforeAll
    fun startVertxTransport() {
        vertx = Vertx.vertx()
        val router = Router.router(vertx)
        adapter = QuarkusHttpServer().also { it.router = router }
        transport = vertx.createHttpServer(HttpServerOptions().setHttp2ClearTextEnabled(false))
            .requestHandler(router).listen(0, LOOPBACK).await()
        endpoint = URI.create("http://$LOOPBACK:${transport.actualPort()}/")
    }

    @AfterAll
    fun stopVertxTransport() {
        if (::transport.isInitialized) {
            transport.close().await()
        }
        if (::vertx.isInitialized) {
            vertx.close().await()
        }
    }

    override fun createServer(): HttpServer = adapter

    override fun baseUri(): URI = endpoint

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CLOSE_TIMEOUT_SECONDS = 10L
    }
}

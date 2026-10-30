package dev.museframework.connectors.http.quarkus

import dev.museframework.engine.api.streaming.StreamingStrategy
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.server.HttpListenerServerInitializer
import dev.museframework.http.api.server.HttpServer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class QuarkusHttpListenerServerInitializer : HttpListenerServerInitializer {
    @Inject
    lateinit var server: QuarkusHttpServer

    private val servers = ConcurrentHashMap<String, HttpServer>()

    override fun initialize(config: HttpListenerConnectionConfig): HttpServer {
        val key = "${config.host}:${config.port}"
        return servers.getOrPut(key) { server }
    }
}

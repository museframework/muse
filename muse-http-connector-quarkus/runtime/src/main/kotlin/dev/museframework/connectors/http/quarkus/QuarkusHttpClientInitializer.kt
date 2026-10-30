package dev.museframework.connectors.http.quarkus

import dev.museframework.http.api.client.HttpClientInitializer
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.http.api.processor.HttpClient
import io.vertx.core.Vertx
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class QuarkusHttpClientInitializer @Inject constructor(
    private val vertx: Vertx,
) : HttpClientInitializer {
    private val cache = ConcurrentHashMap<ClientConfigurationKey, QuarkusWebClientHttpClient>()

    override fun initialize(config: HttpRequestConnectionConfig): HttpClient {
        val key = ClientConfigurationKey.from(config)
        return cache.getOrPut(key) { QuarkusWebClientHttpClient(vertx, config) }
    }

    @PreDestroy
    fun close() {
        cache.values.forEach(HttpClient::close)
        cache.clear()
    }

    private data class ClientConfigurationKey(
        val protocol: HttpProtocol,
        val host: String,
        val port: Int,
        val basePath: String,
        val connectionTimeoutMs: Long,
        val connectionIdleTimeoutMs: Long,
        val responseTimeoutMs: Long,
        val enableCookies: Boolean,
        val followRedirects: Boolean,
        val usePersistentConnections: Boolean,
        val maxConnections: Int,
    ) {
        companion object {
            fun from(config: HttpRequestConnectionConfig): ClientConfigurationKey =
                ClientConfigurationKey(
                    protocol = config.protocol,
                    host = config.host,
                    port = config.port,
                    basePath = config.basePath,
                    connectionTimeoutMs = config.connectionTimeoutMs,
                    connectionIdleTimeoutMs = config.connectionIdleTimeoutMs,
                    responseTimeoutMs = config.responseTimeoutMs,
                    enableCookies = config.enableCookies,
                    followRedirects = config.followRedirects,
                    usePersistentConnections = config.usePersistentConnections,
                    maxConnections = config.maxConnections,
                )
        }
    }
}

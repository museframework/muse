package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.StreamingStrategy
import dev.museframework.http.api.client.HttpClientInitializer
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.processor.HttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Spring-based implementation of [HttpClientInitializer] with client caching.
 *
 * This initializer maintains a thread-safe cache of [SpringHttpClient] instances.
 * Each complete connection configuration owns its transport settings and cookie jar.
 *
 * @see HttpClientInitializer
 * @see SpringHttpClient
 */
class SpringHttpClientInitializer(
    private val streamingStrategy: StreamingStrategy = StreamingStrategy.RepeatableFileStore(),
) : HttpClientInitializer {

    private val clientCache = ConcurrentHashMap<HttpRequestConnectionConfig, SpringHttpClient>()

    /**
     * Initializes and returns an HTTP client configured with the provided connection settings.
     *
     * This method uses a thread-safe cache to reuse clients with identical configuration.
     * The cache key includes protocol, host, port, connection timeout, and response timeout.
     *
     * @param config The connection configuration for the HTTP client
     * @return A configured [HttpClient] instance ready for executing requests
     */
    override fun initialize(config: HttpRequestConnectionConfig): HttpClient =
        clientCache.getOrPut(config) {
            SpringHttpClient(config, streamingStrategy)
        }
}

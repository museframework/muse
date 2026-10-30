package dev.museframework.http.api.client

import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.processor.HttpClient
import java.util.*

/**
 * Service provider interface for initializing HTTP client implementations.
 *
 * This interface uses the Java [ServiceLoader] pattern to allow different runtime
 * environments (Spring Boot, Quarkus, standalone) to provide their own HTTP client
 * implementations without the core module having framework dependencies.
 *
 * Implementations of this interface should be registered in
 * `META-INF/services/dev.museframework.http.api.client.HttpClientInitializer`
 *
 * @see HttpClient
 * @see HttpRequestConnectionConfig
 */
interface HttpClientInitializer {

    /**
     * Initializes and returns an HTTP client configured with the provided connection settings.
     *
     * @param config The connection configuration for the HTTP client
     * @return A configured [HttpClient] instance ready for executing requests
     */
    fun initialize(config: HttpRequestConnectionConfig): HttpClient

    companion object {
        /**
         * Loads the first available [HttpClientInitializer] implementation from the classpath
         * using Java's [ServiceLoader] mechanism.
         *
         * @return The first [HttpClientInitializer] found on the classpath
         * @throws IllegalStateException if no implementation is found on the classpath
         */
        fun load(): HttpClientInitializer =
            ServiceLoader.load(HttpClientInitializer::class.java)
                .firstOrNull()
                ?: throw IllegalStateException(
                    "No HttpClientInitializer found on classpath. " +
                            "Add muse-http-connector-spring or another adapter to your dependencies."
                )
    }
}

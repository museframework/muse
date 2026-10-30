package dev.museframework.connectors.http.spring

import dev.museframework.connectors.http.client.httpClientBuilder
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.http.api.client.HttpClientInitializer
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.source.httpListenerSourceBuilder

class HttpConnectorRegistrar(
    private val initializer: SpringHttpListenerServerInitializer,
    private val httpClientInitializer: HttpClientInitializer,
) : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerSource(
            HttpListenerConfig::class,
            httpListenerSourceBuilder(initializer),
        )
        factory.registerConfig(HttpListenerConnectionConfig::class) { config, context ->
            // Store in properties for ByName resolution in SourceBuilder
            // Use config.name as the key (matches the name passed to httpListenerConnection())
            context.properties["http.connection.${config.name}"] = config
            // initialize server for this connection config
            initializer.initialize(config)
        }
        factory.registerConfig(HttpRequestConnectionConfig::class) { config, context ->
            context.properties[config.name] = config
        }

        factory.registerProcessor(
            HttpRequestConfig::class,
            httpClientBuilder(httpClientInitializer),
        )
    }
}

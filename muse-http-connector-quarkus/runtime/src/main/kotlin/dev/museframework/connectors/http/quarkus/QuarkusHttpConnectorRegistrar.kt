package dev.museframework.connectors.http.quarkus

import dev.museframework.connectors.http.client.httpClientBuilder
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.source.httpListenerSourceBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class QuarkusHttpConnectorRegistrar : MuseComponentRegistrar {
    @Inject
    lateinit var serverInitializer: QuarkusHttpListenerServerInitializer

    @Inject
    lateinit var clientInitializer: QuarkusHttpClientInitializer

    override fun register(factory: MuseComponentFactory) {
        factory.registerSource(HttpListenerConfig::class, httpListenerSourceBuilder(serverInitializer))
        factory.registerConfig(HttpListenerConnectionConfig::class) { config, context ->
            context.properties["http.connection.${config.name}"] = config
            serverInitializer.initialize(config)
        }
        factory.registerConfig(HttpRequestConnectionConfig::class) { config, context ->
            context.properties[config.name] = config
        }
        factory.registerProcessor(HttpRequestConfig::class, httpClientBuilder(clientInitializer))
    }
}

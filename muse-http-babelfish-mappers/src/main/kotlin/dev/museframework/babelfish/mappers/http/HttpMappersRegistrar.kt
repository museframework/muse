package dev.museframework.babelfish.mappers.http

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig

object HttpMappersRegistrar {
    fun register(registry: BabelfishRegistry) {
        registry.registerConfig("http", "listener-config", HttpListenerConnectionConfigMapper)
        registry.registerSource("http", "listener", HttpListenerMapper)
        registry.registerConfig("http", "request-config", HttpRequestConnectionConfigMapper)
        registry.registerElement("http", "request", HttpRequestMapper)
    }

    fun registerEmitters(emitter: KotlinCodeEmitter) {
        emitter.registerConfigEmitter(HttpListenerConnectionConfig::class, HttpListenerConnectionConfigCodeEmitter)
        emitter.registerSourceEmitter(HttpListenerConfig::class, HttpListenerCodeEmitter)
        emitter.registerConfigEmitter(HttpRequestConnectionConfig::class, HttpRequestConnectionConfigCodeEmitter)
        emitter.registerElementEmitter(HttpRequestConfig::class, HttpRequestCodeEmitter)
    }
}

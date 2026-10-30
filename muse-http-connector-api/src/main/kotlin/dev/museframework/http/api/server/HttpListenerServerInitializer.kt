package dev.museframework.http.api.server

import dev.museframework.http.api.config.HttpListenerConnectionConfig
import java.util.*

interface HttpListenerServerInitializer {

    fun initialize(config: HttpListenerConnectionConfig): HttpServer

    companion object {
        fun load(): HttpListenerServerInitializer =
            ServiceLoader.load(HttpListenerServerInitializer::class.java)
                .firstOrNull()
                ?: throw IllegalStateException(
                    "No HttpListenerServerInitializer found on classpath. " +
                            "Add muse-http-connector-spring or another adapter to your dependencies."
                )
    }
}
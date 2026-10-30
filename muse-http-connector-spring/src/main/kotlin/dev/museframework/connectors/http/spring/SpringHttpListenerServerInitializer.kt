package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.StreamingStrategy
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.server.HttpListenerServerInitializer
import dev.museframework.http.api.server.HttpServer
import org.springframework.context.ApplicationContext
import java.util.concurrent.ConcurrentHashMap

class SpringHttpListenerServerInitializer(
    private val applicationContext: ApplicationContext? = null,
    private val streamingStrategy: StreamingStrategy = StreamingStrategy.RepeatableFileStore(),
    private val requestResponseSpoolingStrategy: StreamingStrategy.RepeatableFileStore =
        StreamingStrategy.RepeatableFileStore(),
    private val legacyBodyMaterialization: Boolean = false,
) : HttpListenerServerInitializer {

    private val servers = ConcurrentHashMap<String, SpringHttpServer>()

    override fun initialize(config: HttpListenerConnectionConfig): HttpServer {
        val effectivePort = if (config.port == 0) {
            applicationContext
                ?.environment
                ?.getProperty("local.server.port", Int::class.java)
                ?: 8080
        } else {
            config.port
        }

        val key = "${config.host}:${effectivePort}"
        return servers.getOrPut(key) {
            SpringHttpServer(
                streamingStrategy = streamingStrategy,
                requestResponseSpoolingStrategy = requestResponseSpoolingStrategy,
                legacyBodyMaterialization = legacyBodyMaterialization,
            )
        }
    }

    fun servers(): Map<String, SpringHttpServer> = servers.toMap()
}

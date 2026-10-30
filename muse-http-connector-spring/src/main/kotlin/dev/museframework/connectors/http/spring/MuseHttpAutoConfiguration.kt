package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.http.api.client.HttpClientInitializer
import dev.museframework.springboot.MuseAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse

@AutoConfiguration
@AutoConfigureBefore(MuseAutoConfiguration::class)
@EnableConfigurationProperties(MuseHttpStreamingProperties::class)
class MuseHttpAutoConfiguration {
    @Bean
    fun springHttpListenerServerInitializer(
        applicationContext: ApplicationContext,
        streamingProperties: MuseHttpStreamingProperties,
    ): SpringHttpListenerServerInitializer =
        SpringHttpListenerServerInitializer(
            applicationContext = applicationContext,
            streamingStrategy = streamingProperties.toStrategy(),
            requestResponseSpoolingStrategy = streamingProperties.toFileStoreStrategy(),
            legacyBodyMaterialization = streamingProperties.legacyBodyMaterialization,
        )

    @Bean
    fun springHttpClientInitializer(
        streamingProperties: MuseHttpStreamingProperties,
    ): HttpClientInitializer = SpringHttpClientInitializer(streamingProperties.toStrategy())

    @Bean
    fun httpConnectorRegistrar(
        initializer: SpringHttpListenerServerInitializer,
        httpClientInitializer: HttpClientInitializer,
    ): MuseComponentRegistrar = HttpConnectorRegistrar(initializer, httpClientInitializer)

    @Bean
    fun museHttpRouterFunction(initializer: SpringHttpListenerServerInitializer): RouterFunction<ServerResponse> =
        RouterFunctions
            .route()
            .route({ true }) { request ->
                initializer
                    .servers()
                    .values
                    .firstNotNullOfOrNull { it.handle(request) }
                    ?.handle(request)
                    ?: ServerResponse.notFound().build()
            }.build()
}

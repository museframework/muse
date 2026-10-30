package dev.museframework.apikit.spring

import dev.museframework.apikit.connector.ApikitConnectorRegistrar
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class MuseApikitAutoConfiguration {
    @Bean
    fun apikitConnectorRegistrar(): MuseComponentRegistrar = ApikitConnectorRegistrar()
}

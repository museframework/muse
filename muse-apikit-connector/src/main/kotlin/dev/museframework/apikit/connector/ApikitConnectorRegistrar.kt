package dev.museframework.apikit.connector

import dev.museframework.apikit.ApiSpecificationEmptyException
import dev.museframework.apikit.ApiSpecificationNotConfiguredException
import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.apikit.api.model.ApiSpecification
import dev.museframework.apikit.parser.RamlParser
import dev.museframework.apikit.router.apikitPolicyDeclaration
import dev.museframework.engine.api.spi.ConfigBuilder
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import org.slf4j.LoggerFactory

class ApikitConnectorRegistrar : MuseComponentRegistrar {
    private val logger = LoggerFactory.getLogger(ApikitConnectorRegistrar::class.java)

    override fun register(factory: MuseComponentFactory) {
        factory.registerConfig(
            ApikitConfig::class,
            ConfigBuilder { config, context ->
                val classLoader = Thread.currentThread().contextClassLoader
                val specPath =
                    config.spec?.takeIf { it.isNotBlank() }
                        ?: throw ApiSpecificationNotConfiguredException(config.name)
                val spec = RamlParser.parseRaml(specPath, classLoader)
                if (spec.resources.isEmpty()) {
                    throw ApiSpecificationEmptyException(specPath)
                }
                context.properties["apikit.${config.name}.spec"] = spec
                context.properties["apikit.${config.name}.validationsEnabled"] = !config.disableValidations
                logger.info(
                    "Registered API specification for config {} with {} resources",
                    config.name,
                    spec.resources.size,
                )
            },
        )
        // The declaration resolves the specification-derived routing table and declares one
        // prepared handle per routed flow, so no pipeline is ever rebuilt outside its occurrence.
        factory.registerPolicyRouter(ApikitRouter::class, apikitPolicyDeclaration)
    }
}

package dev.museframework.apikit.parser

import dev.museframework.apikit.api.model.ApiSpecification

/**
 * Loads a RAML 1.0 API specification from the application classpath.
 *
 * AMF owns RAML parsing and semantic resolution; this stable Muse entry point
 * exposes only the framework-neutral routing model.
 */
object RamlParser {
    fun parseRaml(specPath: String, classLoader: ClassLoader): ApiSpecification =
        AmfRamlParser.parseRaml(specPath, classLoader)
}

package dev.museframework.babelfish.mappers.http

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.spi.BabelfishMapperProvider

/**
 * Babelfish mapper provider for the HTTP connector component set.
 *
 * This is a thin adapter over [HttpMappersRegistrar] so the Maven plugin can
 * discover HTTP mappings through `ServiceLoader` while direct callers keep
 * using the registrar object.
 */
class HttpMapperProvider : BabelfishMapperProvider {
    override val id: String = "http"

    override fun registerMappings(registry: BabelfishRegistry) {
        HttpMappersRegistrar.register(registry)
    }

    override fun registerEmitters(
        emitter: KotlinCodeEmitter,
        nativeBuild: Boolean,
    ) {
        HttpMappersRegistrar.registerEmitters(emitter)
    }
}

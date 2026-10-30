package dev.museframework.babelfish.mappers.apikit

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.spi.BabelfishMapperProvider

/**
 * Babelfish mapper provider for the APIKit component set.
 *
 * This is a thin adapter over [ApikitMappersRegistrar] so the Maven plugin can
 * discover APIKit mappings through `ServiceLoader` while direct callers keep
 * using the registrar object.
 */
class ApikitMapperProvider : BabelfishMapperProvider {
    override val id: String = "apikit"

    override fun registerMappings(registry: BabelfishRegistry) {
        ApikitMappersRegistrar.register(registry)
    }

    override fun registerEmitters(
        emitter: KotlinCodeEmitter,
        nativeBuild: Boolean,
    ) {
        ApikitMappersRegistrar.registerEmitters(emitter)
    }
}

package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.spi.BabelfishMapperProvider

/**
 * Babelfish mapper provider for the core component set.
 *
 * This is a thin adapter over [CoreMappersRegistrar] so the Maven plugin can
 * discover core mappings through `ServiceLoader` while direct callers keep
 * using the registrar object.
 */
class CoreMapperProvider : BabelfishMapperProvider {
    override val id: String = "core"

    override fun registerMappings(registry: BabelfishRegistry) {
        CoreMappersRegistrar.register(registry)
    }

    override fun registerEmitters(
        emitter: KotlinCodeEmitter,
        nativeBuild: Boolean,
    ) {
        CoreMappersRegistrar.registerEmitters(emitter, nativeBuild)
    }
}

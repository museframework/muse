package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.spi.BabelfishMapperProvider

/**
 * Babelfish mapper provider for the File connector component set.
 */
class FileMapperProvider : BabelfishMapperProvider {
    override val id: String = "file"

    override fun registerMappings(registry: BabelfishRegistry) {
        FileMappersRegistrar.register(registry)
    }

    override fun registerEmitters(
        emitter: KotlinCodeEmitter,
        nativeBuild: Boolean,
    ) {
        FileMappersRegistrar.registerEmitters(emitter)
    }
}

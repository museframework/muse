package dev.museframework.babelfish.mappers.sftp

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.spi.BabelfishMapperProvider

/**
 * Babelfish mapper provider for the SFTP connector component set.
 */
class SftpMapperProvider : BabelfishMapperProvider {
    override val id: String = "sftp"

    override fun registerMappings(registry: BabelfishRegistry) {
        SftpMappersRegistrar.register(registry)
    }

    override fun registerEmitters(
        emitter: KotlinCodeEmitter,
        nativeBuild: Boolean,
    ) {
        SftpMappersRegistrar.registerEmitters(emitter)
    }
}

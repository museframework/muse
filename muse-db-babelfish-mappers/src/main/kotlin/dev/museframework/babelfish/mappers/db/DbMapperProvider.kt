package dev.museframework.babelfish.mappers.db

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.spi.BabelfishMapperProvider

class DbMapperProvider : BabelfishMapperProvider {
    override val id: String = "db"

    override fun registerMappings(registry: BabelfishRegistry) {
        DbMappersRegistrar.register(registry)
    }

    override fun registerEmitters(
        emitter: KotlinCodeEmitter,
        nativeBuild: Boolean,
    ) {
        DbMappersRegistrar.registerEmitters(emitter)
    }
}

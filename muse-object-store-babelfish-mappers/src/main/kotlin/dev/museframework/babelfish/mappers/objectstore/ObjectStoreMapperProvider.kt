package dev.museframework.babelfish.mappers.objectstore

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.spi.BabelfishMapperProvider

class ObjectStoreMapperProvider : BabelfishMapperProvider {
    override val id: String = "object-store"

    override fun registerMappings(registry: BabelfishRegistry) = ObjectStoreMappersRegistrar.register(registry)

    override fun registerEmitters(emitter: KotlinCodeEmitter, nativeBuild: Boolean) =
        ObjectStoreMappersRegistrar.registerEmitters(emitter)
}

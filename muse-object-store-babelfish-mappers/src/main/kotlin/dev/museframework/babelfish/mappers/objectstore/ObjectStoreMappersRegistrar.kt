package dev.museframework.babelfish.mappers.objectstore

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.objectstore.api.config.*

object ObjectStoreMappersRegistrar {
    fun register(registry: BabelfishRegistry) {
        registry.registerConfig("os", "config", ObjectStoreConnectorConfigMapper)
        registry.registerConfig("os", "object-store", ObjectStoreConfigMapper)
        registry.registerElement("os", "clear", ObjectStoreClearMapper)
        registry.registerElement("os", "contains", ObjectStoreContainsMapper)
        registry.registerElement("os", "remove", ObjectStoreRemoveMapper)
        registry.registerElement("os", "retrieve", ObjectStoreRetrieveMapper)
        registry.registerElement("os", "retrieve-all", ObjectStoreRetrieveAllMapper)
        registry.registerElement("os", "retrieve-all-keys", ObjectStoreRetrieveAllKeysMapper)
        registry.registerElement("os", "store", ObjectStoreStoreMapper)
    }

    fun registerEmitters(emitter: KotlinCodeEmitter) {
        emitter.registerConfigEmitter(ObjectStoreConnectorConfig::class, ObjectStoreConnectorConfigCodeEmitter)
        emitter.registerConfigEmitter(ObjectStoreConfig::class, ObjectStoreConfigCodeEmitter)
        emitter.registerElementEmitter(ObjectStoreClearConfig::class, ObjectStoreClearCodeEmitter)
        emitter.registerElementEmitter(ObjectStoreContainsConfig::class, ObjectStoreContainsCodeEmitter)
        emitter.registerElementEmitter(ObjectStoreRemoveConfig::class, ObjectStoreRemoveCodeEmitter)
        emitter.registerElementEmitter(ObjectStoreRetrieveConfig::class, ObjectStoreRetrieveCodeEmitter)
        emitter.registerElementEmitter(ObjectStoreRetrieveAllConfig::class, ObjectStoreRetrieveAllCodeEmitter)
        emitter.registerElementEmitter(ObjectStoreRetrieveAllKeysConfig::class, ObjectStoreRetrieveAllKeysCodeEmitter)
        emitter.registerElementEmitter(ObjectStoreStoreConfig::class, ObjectStoreStoreCodeEmitter)
    }
}

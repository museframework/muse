package dev.museframework.connectors.objectstore

import dev.museframework.connectors.objectstore.internal.ObjectStoreManager
import dev.museframework.connectors.objectstore.processor.ObjectStoreOperationMuseProcessor
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.engine.api.spi.ProcessorBuilder
import kotlin.reflect.KClass
import dev.museframework.objectstore.api.config.*

class ObjectStoreConnectorRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerConfig(ObjectStoreConnectorConfig::class) { _, context -> manager(context) }
        factory.registerConfig(ObjectStoreConfig::class) { _, context -> manager(context) }
        registerOperation(factory, ObjectStoreClearConfig::class)
        registerOperation(factory, ObjectStoreContainsConfig::class)
        registerOperation(factory, ObjectStoreRemoveConfig::class)
        registerOperation(factory, ObjectStoreRetrieveConfig::class)
        registerOperation(factory, ObjectStoreRetrieveAllConfig::class)
        registerOperation(factory, ObjectStoreRetrieveAllKeysConfig::class)
        registerOperation(factory, ObjectStoreStoreConfig::class)
    }

    private fun <T : ObjectStoreOperationConfig> registerOperation(factory: MuseComponentFactory, type: KClass<T>) {
        factory.registerProcessor(type, processorBuilder())
        factory.registerProcessorBootstrapValidator(type) { component, context ->
            manager(context).resolve(component.objectStore)
        }
    }

    private fun <T : ObjectStoreOperationConfig> processorBuilder(): ProcessorBuilder<T> =
        ProcessorBuilder { component, context ->
            val store = manager(context).resolve(component.objectStore)
            ObjectStoreOperationMuseProcessor(component, context.expressionEvaluator) { store }
        }

    private fun manager(context: MuseContext): ObjectStoreManager =
        synchronized(context.properties) {
            (context.properties[MANAGER_KEY] as? ObjectStoreManager)
                ?: ObjectStoreManager.create(context).also { context.properties[MANAGER_KEY] = it }
        }

    private companion object {
        const val MANAGER_KEY = "muse.object-store.internal.manager"
    }
}

package dev.museframework.connectors.objectstore.backend

import dev.museframework.objectstore.api.config.ObjectStoreConfig
import dev.museframework.objectstore.api.config.ObjectStoreConnectorConfig

/** Install via META-INF/services and select with the `muse.object-store.backend` deployment property. */
interface ObjectStoreBackendProvider {
    val id: String

    /**
     * Open one store for the application. [identity] is `default` or `named:<name>`; [properties]
     * contains string deployment properties. The runtime owns the returned store and closes it.
     */
    fun open(
        config: ObjectStoreConfig,
        connector: ObjectStoreConnectorConfig?,
        identity: String,
        properties: Map<String, String>,
    ): ObjectStoreBackend
}

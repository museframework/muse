package dev.museframework.connectors.objectstore.internal

import dev.museframework.connectors.objectstore.backend.ObjectStoreBackend
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackendProvider
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.engine.api.spi.ApplicationStartableResource
import dev.museframework.objectstore.api.config.ObjectStoreConfig
import dev.museframework.objectstore.api.config.ObjectStoreConnectorConfig
import dev.museframework.objectstore.api.config.ObjectStoreConnectorRef
import dev.museframework.objectstore.api.config.ObjectStoreRef
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.UUID
import java.util.ServiceLoader

internal class ObjectStoreManager private constructor(
    private val defaultStore: ObjectStoreBackend,
    private val namedStores: Map<String, ObjectStoreBackend>,
    private val expiration: ObjectStoreExpiration?,
) : ApplicationStartableResource {
    private var closed = false
    private var started = false

    @Synchronized
    override fun start() {
        check(!closed) { "Object Store manager is closed" }
        if (started) return
        expiration?.let { scheduler ->
            (listOf(defaultStore) + namedStores.values).filterIsInstance<H2ObjectStore>().forEach(scheduler::schedule)
        }
        started = true
    }

    fun resolve(ref: ObjectStoreRef): ObjectStoreBackend = when (ref) {
        ObjectStoreRef.Default -> defaultStore
        is ObjectStoreRef.ByName -> namedStores[ref.name] ?: throw IllegalArgumentException("Unknown Object Store: ${ref.name}")
        is ObjectStoreRef.ByReference -> namedStores[ref.config.name] ?: throw IllegalArgumentException("Unknown Object Store: ${ref.config.name}")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try { expiration?.close() } catch (cause: Throwable) { failure = cause }
        (listOf(defaultStore) + namedStores.values).asReversed().forEach { store ->
            try { store.close() } catch (cause: Throwable) {
                val previous = failure
                if (previous == null) failure = cause else previous.addSuppressed(cause)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        fun create(context: MuseContext, clock: Clock = Clock.systemUTC()): ObjectStoreManager {
            val backend = context.properties.getProperty("muse.object-store.backend", "h2").trim()
            require(backend.isNotEmpty()) { "Object Store backend ID must not be blank" }
            val configs = context.configurationDeclarations.filterIsInstance<ObjectStoreConfig>()
            val connectors = context.configurationDeclarations.filterIsInstance<ObjectStoreConnectorConfig>()
            val manager = if (backend == "h2") {
                val configuredDirectory = context.properties.getProperty("muse.object-store.data-dir")
                val directory = if (configuredDirectory != null) Path.of(configuredDirectory) else {
                    val workingDirectory = Path.of(System.getProperty("user.dir")).toRealPath().toString()
                    val hash = MessageDigest.getInstance("SHA-256").digest(workingDirectory.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    Path.of(System.getProperty("user.home"), ".muse", "object-store", hash)
                }
                open(configs, connectors, directory, context.properties.getProperty("muse.object-store.namespace", "default"), clock)
            } else {
                val providers = ServiceLoader.load(ObjectStoreBackendProvider::class.java).filter { it.id == backend }.toList()
                require(providers.size == 1) {
                    if (providers.isEmpty()) "Unknown Object Store backend: $backend"
                    else "Multiple Object Store backend providers have ID: $backend"
                }
                val properties = context.properties.stringPropertyNames().associateWith { context.properties.getProperty(it)!! }
                openBackends(configs, connectors, null) { config, identity, connector ->
                    providers.single().open(config, connector, identity, properties)
                }
            }
            try { context.resources.register(manager) } catch (cause: Throwable) {
                try { manager.close() } catch (close: Throwable) { cause.addSuppressed(close) }
                throw cause
            }
            return manager
        }

        fun open(
            configs: List<ObjectStoreConfig>,
            connectors: List<ObjectStoreConnectorConfig>,
            dataDirectory: Path,
            namespace: String,
            clock: Clock = Clock.systemUTC(),
        ): ObjectStoreManager {
            require(namespace.isNotBlank()) { "Object Store namespace must not be blank" }
            val expiration = ObjectStoreExpiration()
            return openBackends(configs, connectors, expiration) { config, identity, connectorConfig ->
                val url = if (config.persistent) {
                    val namespaceDirectory = dataDirectory.toAbsolutePath().normalize().resolve(segment(namespace))
                    Files.createDirectories(namespaceDirectory)
                    "jdbc:h2:file:${namespaceDirectory.resolve(segment(identity))}"
                } else "jdbc:h2:mem:muse-os-${UUID.randomUUID()}"
                H2ObjectStore(config, url, connectorConfig?.lockTimeout, clock)
            }
        }

        private fun openBackends(
            configs: List<ObjectStoreConfig>,
            connectors: List<ObjectStoreConnectorConfig>,
            expiration: ObjectStoreExpiration?,
            openStore: (ObjectStoreConfig, String, ObjectStoreConnectorConfig?) -> ObjectStoreBackend,
        ): ObjectStoreManager {
            require(configs.map { it.name }.distinct().size == configs.size) { "Duplicate Object Store name" }
            require(connectors.map { it.name }.distinct().size == connectors.size) { "Duplicate Object Store connector name" }
            val connectorsByName = connectors.associateBy { it.name }
            fun connector(config: ObjectStoreConfig): ObjectStoreConnectorConfig? = when (val ref = config.connector) {
                ObjectStoreConnectorRef.Default -> null
                is ObjectStoreConnectorRef.ByName -> connectorsByName[ref.name] ?: throw IllegalArgumentException("Unknown Object Store connector: ${ref.name}")
                is ObjectStoreConnectorRef.ByReference -> connectorsByName[ref.config.name] ?: throw IllegalArgumentException("Unknown Object Store connector: ${ref.config.name}")
            }
            val resolvedConnectors = configs.associate { it.name to connector(it) }
            val opened = mutableListOf<ObjectStoreBackend>()
            try {
                val default = openStore(ObjectStoreConfig("default"), "default", null).also(opened::add)
                val named = configs.associate { config ->
                    config.name to openStore(config, "named:${config.name}", resolvedConnectors[config.name]).also(opened::add)
                }
                return ObjectStoreManager(default, named, expiration)
            } catch (cause: Throwable) {
                try { expiration?.close() } catch (close: Throwable) { cause.addSuppressed(close) }
                opened.asReversed().forEach { store ->
                    try { store.close() } catch (close: Throwable) { cause.addSuppressed(close) }
                }
                throw cause
            }
        }

        private fun segment(name: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))
    }
}

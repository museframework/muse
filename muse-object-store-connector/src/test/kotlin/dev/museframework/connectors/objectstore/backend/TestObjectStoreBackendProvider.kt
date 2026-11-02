package dev.museframework.connectors.objectstore.backend

import dev.museframework.engine.api.event.TypedValue
import dev.museframework.objectstore.api.config.ObjectStoreConfig
import dev.museframework.objectstore.api.config.ObjectStoreConnectorConfig
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class TestObjectStoreBackendProvider : ObjectStoreBackendProvider {
    override val id: String = "test-memory"

    override fun open(
        config: ObjectStoreConfig,
        connector: ObjectStoreConnectorConfig?,
        identity: String,
        properties: Map<String, String>,
    ): ObjectStoreBackend {
        if (identity == failOnIdentity) throw IllegalStateException("Cannot open $identity")
        return TestObjectStoreBackend(identity, connector?.lockTimeout).also(opened::add)
    }

    companion object {
        val opened = CopyOnWriteArrayList<TestObjectStoreBackend>()
        @Volatile var failOnIdentity: String? = null

        fun reset() {
            opened.clear()
            failOnIdentity = null
        }
    }
}

class FirstDuplicateBackendProvider : ObjectStoreBackendProvider {
    override val id: String = "test-duplicate"
    override fun open(config: ObjectStoreConfig, connector: ObjectStoreConnectorConfig?, identity: String, properties: Map<String, String>): ObjectStoreBackend =
        TestObjectStoreBackend(identity, connector?.lockTimeout)
}

class SecondDuplicateBackendProvider : ObjectStoreBackendProvider {
    override val id: String = "test-duplicate"
    override fun open(config: ObjectStoreConfig, connector: ObjectStoreConnectorConfig?, identity: String, properties: Map<String, String>): ObjectStoreBackend =
        TestObjectStoreBackend(identity, connector?.lockTimeout)
}

class TestObjectStoreBackend(val identity: String, override val lockTimeout: Duration?) : ObjectStoreBackend {
    private val values = linkedMapOf<String, ByteArray>()
    var closed: Boolean = false
        private set

    override suspend fun <T> withLock(timeout: Duration?, action: () -> T): T = synchronized(values) {
        check(!closed)
        action()
    }

    override fun clear() { check(!closed); values.clear() }
    override fun contains(key: String): Boolean { check(!closed); return values.containsKey(key) }
    override fun remove(key: String) {
        check(!closed)
        if (values.remove(key) == null) throw ObjectStoreBackendKeyNotFoundException(key)
    }
    override fun retrieve(key: String): TypedValue<*>? { check(!closed); return values[key]?.let { TypedValue.of(it.toString(Charsets.UTF_8)) } }
    override fun retrieveAll(): Map<String, TypedValue<*>> { check(!closed); return values.mapValues { TypedValue.of(it.value.toString(Charsets.UTF_8)) } }
    override fun retrieveAllKeys(): List<String> { check(!closed); return values.keys.toList() }
    override fun snapshot(value: TypedValue<*>): ByteArray = (value.value as String).toByteArray(Charsets.UTF_8)
    override fun putSnapshot(key: String, encoded: ByteArray, failIfPresent: Boolean) {
        check(!closed)
        if (failIfPresent && values.containsKey(key)) throw ObjectStoreBackendKeyAlreadyExistsException(key)
        values[key] = encoded.copyOf()
    }

    override fun close() { closed = true }
}

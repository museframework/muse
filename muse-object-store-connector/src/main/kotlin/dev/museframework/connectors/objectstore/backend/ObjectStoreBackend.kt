package dev.museframework.connectors.objectstore.backend

import dev.museframework.engine.api.event.TypedValue
import java.time.Duration

/** One application-owned store. Operations inside [withLock] must remain synchronous and process-local. */
interface ObjectStoreBackend : AutoCloseable {
    val lockTimeout: Duration?

    /** Acquire the backend's application-local operation lock, checking cancellation before [action]. */
    suspend fun <T> withLock(timeout: Duration? = lockTimeout, action: () -> T): T

    fun clear()
    fun contains(key: String): Boolean
    fun remove(key: String)
    fun retrieve(key: String): TypedValue<*>?
    fun retrieveAll(): Map<String, TypedValue<*>>
    fun retrieveAllKeys(): List<String>

    /** Materialize and validate a value before acquiring the mutation lock. */
    fun snapshot(value: TypedValue<*>): ByteArray

    /** Commit a previously materialized value; [failIfPresent] must be atomic for this backend. */
    fun putSnapshot(key: String, encoded: ByteArray, failIfPresent: Boolean)

    fun put(key: String, value: TypedValue<*>, failIfPresent: Boolean) =
        putSnapshot(key, snapshot(value), failIfPresent)
}

/** Exceptions that providers can use to preserve the connector's public error categories. */
open class ObjectStoreBackendKeyAlreadyExistsException(val key: String) : IllegalStateException("Object Store key already exists: $key")
open class ObjectStoreBackendKeyNotFoundException(val key: String) : IllegalStateException("Object Store key not found: $key")
open class ObjectStoreBackendValueValidationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
open class ObjectStoreBackendCorruptionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

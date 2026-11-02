package dev.museframework.connectors.objectstore.internal

import dev.museframework.engine.api.event.TypedValue
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackend
import dev.museframework.objectstore.api.config.ObjectStoreConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The owner connection and fair application lock coordinate operations and expiration. */
internal class H2ObjectStore(
    val config: ObjectStoreConfig,
    jdbcUrl: String,
    override val lockTimeout: Duration? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val codec: ObjectStoreValueCodec = ObjectStoreValueCodec(),
) : ObjectStoreBackend {
    private val lock = ReentrantLock(true)
    private val connection: Connection = DriverManager.getConnection("$jdbcUrl;LOCK_TIMEOUT=1000;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE")
    private var closed = false

    init { try { initialize() } catch (cause: Throwable) { connection.close(); throw cause } }

    override suspend fun <T> withLock(timeout: Duration?, action: () -> T): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        val started = System.nanoTime()
        val timeoutNanos = timeout?.toNanos()
        while (true) {
            context.ensureActive()
            val remaining = timeoutNanos?.let { it - (System.nanoTime() - started) }
            val acquired = when {
                timeoutNanos == 0L -> lock.tryLock(0, TimeUnit.NANOSECONDS)
                remaining != null && remaining <= 0 -> false
                else -> lock.tryLock(minOf(remaining ?: POLL_NANOS, POLL_NANOS), TimeUnit.NANOSECONDS)
            }
            if (acquired) {
                try { context.ensureActive(); return action() } finally { lock.unlock() }
            }
            if (timeoutNanos != null && System.nanoTime() - started >= timeoutNanos) {
                throw TimeoutException("Timed out acquiring Object Store lock for ${config.name}")
            }
        }
    }

    override fun contains(key: String): Boolean = operation {
        connection.prepareStatement("SELECT 1 FROM muse_os_entries WHERE entry_key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { it.next() }
        }
    }

    override fun retrieve(key: String): TypedValue<*>? = operation {
        connection.prepareStatement("SELECT entry_value FROM muse_os_entries WHERE entry_key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { if (it.next()) codec.decode(it.getBytes(1)) else null }
        }
    }

    override fun retrieveAll(): Map<String, TypedValue<*>> = operation {
        val result = linkedMapOf<String, TypedValue<*>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT entry_key, entry_value FROM muse_os_entries ORDER BY entry_key").use { rows ->
                while (rows.next()) result[rows.getString(1)] = codec.decode(rows.getBytes(2))
            }
        }
        Collections.unmodifiableMap(result)
    }

    override fun retrieveAllKeys(): List<String> = operation {
        val result = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT entry_key FROM muse_os_entries ORDER BY entry_key").use { rows ->
                while (rows.next()) result.add(rows.getString(1))
            }
        }
        Collections.unmodifiableList(result)
    }

    override fun snapshot(value: TypedValue<*>): ByteArray = codec.encode(value)

    override fun put(key: String, value: TypedValue<*>, failIfPresent: Boolean) =
        putSnapshot(key, snapshot(value), failIfPresent)

    override fun putSnapshot(key: String, encoded: ByteArray, failIfPresent: Boolean) = operation {
        val now = clock.instant()
        val sql = if (failIfPresent) {
            "INSERT INTO muse_os_entries(entry_key, entry_value, written_at, written_nanos) VALUES (?, ?, ?, ?)"
        } else {
            "MERGE INTO muse_os_entries(entry_key, entry_value, written_at, written_nanos) KEY(entry_key) VALUES (?, ?, ?, ?)"
        }
        transaction {
            try {
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, key); statement.setBytes(2, encoded)
                    statement.setLong(3, now.epochSecond); statement.setInt(4, now.nano)
                    statement.executeUpdate()
                }
            } catch (cause: SQLException) {
                if (failIfPresent && cause.sqlState == "23505") throw ObjectStoreKeyAlreadyExistsException(key)
                throw cause
            }
        }
        Unit
    }

    override fun remove(key: String) = operation {
        transaction {
            connection.prepareStatement("DELETE FROM muse_os_entries WHERE entry_key = ?").use { statement ->
                statement.setString(1, key)
                if (statement.executeUpdate() == 0) throw ObjectStoreKeyNotFoundException(key)
            }
        }
        Unit
    }

    override fun clear() = operation {
        transaction { connection.createStatement().use { it.executeUpdate("DELETE FROM muse_os_entries") } }
        Unit
    }

    fun sweep() = operation {
        transaction {
            config.entryTtl?.takeIf { !it.isZero && !it.isNegative }?.let { ttl ->
                val cutoff = clock.instant().minus(ttl)
                connection.prepareStatement("DELETE FROM muse_os_entries WHERE written_at < ? OR (written_at = ? AND written_nanos <= ?)").use { statement ->
                    statement.setLong(1, cutoff.epochSecond); statement.setLong(2, cutoff.epochSecond)
                    statement.setInt(3, cutoff.nano); statement.executeUpdate()
                }
            }
            config.maxEntries?.let { limit ->
                connection.prepareStatement("DELETE FROM muse_os_entries WHERE entry_key IN (SELECT entry_key FROM muse_os_entries ORDER BY written_at DESC, written_nanos DESC, entry_key DESC OFFSET ? ROWS)").use { statement ->
                    statement.setInt(1, limit); statement.executeUpdate()
                }
            }
        }
        Unit
    }

    override fun close() = lock.withLock {
        if (!closed) { closed = true; connection.close() }
    }

    private fun initialize() {
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE IF NOT EXISTS muse_os_metadata (id INT PRIMARY KEY, schema_version INT NOT NULL, codec_version INT NOT NULL, ttl VARCHAR, max_entries INT)")
        }
        connection.prepareStatement("SELECT schema_version, codec_version, ttl, max_entries FROM muse_os_metadata WHERE id = 1").use { statement ->
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    val maxEntries = rows.getObject(4) as? Int
                    if (rows.getInt(1) != SCHEMA_VERSION || rows.getInt(2) != ObjectStoreValueCodec.VERSION ||
                        rows.getString(3) != effectiveTtl() || maxEntries != config.maxEntries) {
                        throw ObjectStoreCorruptionException("Incompatible Object Store metadata for ${config.name}")
                    }
                } else {
                    connection.prepareStatement("INSERT INTO muse_os_metadata VALUES (1, ?, ?, ?, ?)").use { insert ->
                        insert.setInt(1, SCHEMA_VERSION); insert.setInt(2, ObjectStoreValueCodec.VERSION)
                        insert.setString(3, effectiveTtl()); insert.setObject(4, config.maxEntries); insert.executeUpdate()
                    }
                }
            }
        }
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE IF NOT EXISTS muse_os_entries (entry_key VARCHAR PRIMARY KEY, entry_value BLOB NOT NULL, written_at BIGINT NOT NULL, written_nanos INT NOT NULL)")
        }
    }

    private fun effectiveTtl(): String? = config.entryTtl?.takeIf { !it.isNegative && !it.isZero }?.toString()

    private inline fun <T> operation(action: () -> T): T = lock.withLock {
        check(!closed) { "Object Store ${config.name} is closed" }
        action()
    }

    private inline fun <T> transaction(action: () -> T): T {
        connection.autoCommit = false
        try {
            val result = action(); connection.commit(); return result
        } catch (cause: Throwable) {
            try { connection.rollback() } catch (rollback: Throwable) { cause.addSuppressed(rollback) }
            throw cause
        } finally { connection.autoCommit = true }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(20)
    }
}

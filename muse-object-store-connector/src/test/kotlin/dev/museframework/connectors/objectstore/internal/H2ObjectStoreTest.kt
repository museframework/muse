package dev.museframework.connectors.objectstore.internal

import dev.museframework.engine.api.event.TypedValue
import dev.museframework.objectstore.api.config.ObjectStoreConfig
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.*
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.*

class H2ObjectStoreTest {
    private fun store(config: ObjectStoreConfig = ObjectStoreConfig("test", persistent = false), clock: Clock = Clock.systemUTC()): H2ObjectStore =
        H2ObjectStore(config, "jdbc:h2:mem:${UUID.randomUUID()}", clock = clock)

    @Test
    fun `transient owner retains values and list results are sorted snapshots`() = store().use { store ->
        store.put("z", TypedValue.of("old"), false)
        store.put("a", TypedValue.of("first"), false)
        val keys = store.retrieveAllKeys()
        val values = store.retrieveAll()
        store.put("z", TypedValue.of("new"), false)
        store.remove("a")
        assertEquals(listOf("a", "z"), keys)
        assertEquals("old", values["z"]?.value)
        assertEquals("new", store.retrieve("z")?.value)
        assertFalse(store.contains("a"))
        store.clear()
        assertTrue(store.retrieveAll().isEmpty())
    }

    @Test
    fun `concurrent fail if present inserts exactly one winner`() = store().use { store ->
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (1..30).map { value -> executor.submit<Boolean> {
                try { store.put("key", TypedValue.of(value), true); true } catch (_: ObjectStoreKeyAlreadyExistsException) { false }
            } }.map { it.get() }
            assertEquals(1, results.count { it })
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `failed conversion leaves previous value intact`() = store().use { store ->
        store.put("key", TypedValue.of("original"), false)
        assertFailsWith<ObjectStoreValueValidationException> { store.put("key", TypedValue.of(Any()), false) }
        assertEquals("original", store.retrieve("key")?.value)
    }

    @Test
    fun `ttl is periodic and overwrite resets age`() {
        val clock = MutableClock()
        store(ObjectStoreConfig("test", persistent = false, entryTtl = Duration.ofSeconds(10)), clock).use { store ->
            store.put("old", TypedValue.of(1), false)
            clock.millis = 9000
            store.put("fresh", TypedValue.of(2), false)
            store.put("reset", TypedValue.of(1), false)
            clock.millis = 11000
            store.put("reset", TypedValue.of(3), false)
            assertTrue(store.contains("old"))
            store.sweep()
            assertEquals(listOf("fresh", "reset"), store.retrieveAllKeys())
        }
    }

    @Test
    fun `capacity still sweeps with zero or negative ttl and ties use lexical keys`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { ttl ->
            store(ObjectStoreConfig("test", persistent = false, maxEntries = 2, entryTtl = ttl), MutableClock()).use { store ->
                listOf("c", "a", "b").forEach { store.put(it, TypedValue.of(it), false) }
                store.sweep()
                assertEquals(listOf("b", "c"), store.retrieveAllKeys())
            }
        }
    }

    @Test
    fun `unknown schema is rejected without rewriting it`() {
        val url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().execute("CREATE TABLE muse_os_metadata (id INT PRIMARY KEY, schema_version INT, codec_version INT, ttl VARCHAR, max_entries INT)")
            connection.createStatement().execute("INSERT INTO muse_os_metadata VALUES (1, 99, 1, NULL, NULL)")
        }
        assertFailsWith<ObjectStoreCorruptionException> { H2ObjectStore(ObjectStoreConfig("test"), url) }
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().executeQuery("SELECT schema_version FROM muse_os_metadata").use { assertTrue(it.next()); assertEquals(99, it.getInt(1)) }
            connection.createStatement().execute("SHUTDOWN")
        }
    }
}

internal class MutableClock(var millis: Long = 0) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
    override fun millis(): Long = millis
}

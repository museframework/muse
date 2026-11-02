package dev.museframework.connectors.objectstore.internal

import dev.museframework.engine.api.event.TypedValue
import dev.museframework.objectstore.api.config.ObjectStoreConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ObjectStorePersistenceIT {
    @TempDir lateinit var directory: Path

    @Test
    fun `acknowledged writes survive hard process kill and namespace isolation`() {
        val writer = process("write", "app-one").start()
        try {
            val line = writer.inputReader().readLine()
            assertEquals("ACK", line)
            writer.destroyForcibly()
            assertTrue(writer.waitFor(20, TimeUnit.SECONDS))
        } finally { if (writer.isAlive) writer.destroyForcibly() }
        assertEquals("persisted", readProcess("app-one"))
        assertEquals("missing", readProcess("app-two"))
    }

    @Test
    fun `restart retains timestamps until first full sweep interval and rejects settings changes`() {
        val clock = MutableClock()
        val config = ObjectStoreConfig("named", entryTtl = Duration.ofSeconds(1), expirationInterval = Duration.ofMillis(200))
        ObjectStoreManager.open(listOf(config), emptyList(), directory, "restart", clock).use { manager ->
            manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.ByName("named")).put("key", TypedValue.of("old"), false)
        }
        clock.millis = 2000
        ObjectStoreManager.open(listOf(config), emptyList(), directory, "restart", clock).use { manager ->
            val store = manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.ByName("named"))
            assertTrue(store.contains("key"))
            manager.start()
            assertTrue(store.contains("key"))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (store.contains("key") && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(store.contains("key"))
        }
        assertFailsWith<ObjectStoreCorruptionException> {
            ObjectStoreManager.open(listOf(config.copy(maxEntries = 1)), emptyList(), directory, "restart", clock)
        }
        ObjectStoreManager.open(listOf(config.copy(persistent = false)), emptyList(), directory, "restart", clock).use { manager ->
            assertFalse(manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.ByName("named")).contains("key"))
        }
    }

    @Test
    fun `named stores never share keys and second process cannot open a live database`() {
        val first = ObjectStoreConfig("first")
        val second = ObjectStoreConfig("second")
        ObjectStoreManager.open(listOf(first, second), emptyList(), directory, "app-one").use { manager ->
            manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.ByName("first")).put("key", TypedValue.of("one"), false)
            assertFalse(manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.ByName("second")).contains("key"))
            val child = process("read", "app-one").start()
            assertTrue(child.waitFor(20, TimeUnit.SECONDS))
            assertNotEquals(0, child.exitValue())
        }
    }

    @Test
    fun `switch to transient preserves prior persistent state and named default stays separate`() {
        val config = ObjectStoreConfig("default")
        val named = dev.museframework.objectstore.api.config.ObjectStoreRef.ByName("default")
        ObjectStoreManager.open(listOf(config), emptyList(), directory, "config-switch").use { manager ->
            manager.resolve(named).put("key", TypedValue.of("named-value"), false)
            assertFalse(manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.Default).contains("key"))
        }
        ObjectStoreManager.open(listOf(config.copy(persistent = false)), emptyList(), directory, "config-switch").use { manager ->
            assertFalse(manager.resolve(named).contains("key"))
            manager.resolve(named).put("key", TypedValue.of("transient-value"), false)
        }
        ObjectStoreManager.open(listOf(config), emptyList(), directory, "config-switch").use { manager ->
            assertEquals("named-value", manager.resolve(named).retrieve("key")?.value)
        }
    }

    @Test
    fun `expiration waits for application start even after a full bootstrap interval`() {
        val config = ObjectStoreConfig("waiting", persistent = false, maxEntries = 1, expirationInterval = Duration.ofMillis(50))
        ObjectStoreManager.open(listOf(config), emptyList(), directory, "waiting", MutableClock()).use { manager ->
            val store = manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.ByName("waiting"))
            store.put("a", TypedValue.of(1), false)
            store.put("b", TypedValue.of(2), false)
            Thread.sleep(150)
            assertEquals(listOf("a", "b"), store.retrieveAllKeys())
            manager.start()
            manager.start()
            assertEquals(listOf("a", "b"), store.retrieveAllKeys())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (store.retrieveAllKeys().size > 1 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(listOf("b"), store.retrieveAllKeys())
        }
    }

    @Test
    fun `capacity scheduler runs with negative ttl and interval zero disables cleanup`() {
        val active = ObjectStoreConfig("active", persistent = false, maxEntries = 1, entryTtl = Duration.ofSeconds(-1), expirationInterval = Duration.ofMillis(100))
        val disabled = active.copy(name = "disabled", expirationInterval = Duration.ZERO)
        ObjectStoreManager.open(listOf(active, disabled), emptyList(), directory, "capacity", MutableClock()).use { manager ->
            val activeStore = manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.ByName("active"))
            val disabledStore = manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.ByName("disabled"))
            listOf(activeStore, disabledStore).forEach { store ->
                store.put("a", TypedValue.of(1), false)
                store.put("b", TypedValue.of(2), false)
            }
            manager.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (activeStore.retrieveAllKeys().size > 1 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(listOf("b"), activeStore.retrieveAllKeys())
            assertEquals(listOf("a", "b"), disabledStore.retrieveAllKeys())
        }
    }

    private fun readProcess(namespace: String): String {
        val reader = process("read", namespace).start()
        assertTrue(reader.waitFor(20, TimeUnit.SECONDS))
        val output = reader.inputReader().readText().trim()
        assertEquals(0, reader.exitValue(), output)
        return output
    }

    private fun process(mode: String, namespace: String) = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
        System.getProperty("surefire.test.class.path") ?: System.getProperty("java.class.path"),
        ObjectStoreProcessProbe::class.java.name, mode, directory.toString(), namespace,
    ).redirectError(ProcessBuilder.Redirect.INHERIT)
}

object ObjectStoreProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        ObjectStoreManager.open(emptyList(), emptyList(), Path.of(args[1]), args[2]).use { manager ->
            val store = manager.resolve(dev.museframework.objectstore.api.config.ObjectStoreRef.Default)
            if (args[0] == "write") {
                store.put("key", TypedValue.of("persisted"), false)
                println("ACK")
                System.out.flush()
                Thread.sleep(60000)
            } else {
                println(store.retrieve("key")?.value ?: "missing")
            }
        }
    }
}

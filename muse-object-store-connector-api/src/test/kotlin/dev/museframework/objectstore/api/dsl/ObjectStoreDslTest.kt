package dev.museframework.objectstore.api.dsl

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.objectstore.api.config.*
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.*

class ObjectStoreDslTest {
    @Test
    fun `direct store configurations use their names for bootstrap identity`() {
        val stores = listOf(ObjectStoreConfig("tokens"), ObjectStoreConfig("watermarks"))
        assertEquals(listOf("tokens", "watermarks"), stores.map { it.docName })
        assertEquals(2, stores.associateBy { it.docName }.size)
    }

    @Test
    fun `direct connector configurations use their names for bootstrap identity`() {
        val connectors = listOf(ObjectStoreConnectorConfig("fast"), ObjectStoreConnectorConfig("slow"))
        assertEquals(listOf("fast", "slow"), connectors.map { it.docName })
        assertEquals(2, connectors.associateBy { it.docName }.size)
    }

    @Test
    fun `store DSL uses the configured name when documentation name is blank`() {
        val store = objectStore { name = "tokens" }
        val blankDocumentation = objectStore { name = "watermarks"; docName = " " }
        assertEquals("tokens", store.docName)
        assertEquals("watermarks", blankDocumentation.docName)
    }

    @Test
    fun `connector DSL uses the configured name when documentation name is blank`() {
        val connector = objectStoreConnector { name = "fast" }
        val blankDocumentation = objectStoreConnector { name = "slow"; docName = " " }
        assertEquals("fast", connector.docName)
        assertEquals("slow", blankDocumentation.docName)
    }

    @Test
    fun `seven operations preserve declaration order and default store selection`() {
        val configured = flow("operations") {
            objectStoreClear()
            objectStoreContains { key = "#[vars.key]" }
            objectStoreRemove { key = "old" }
            objectStoreRetrieve { key = "token" }
            objectStoreRetrieveAll()
            objectStoreRetrieveAllKeys()
            objectStoreStore { key = "token" }
        }
        assertEquals(
            listOf(
                ObjectStoreClearConfig::class, ObjectStoreContainsConfig::class, ObjectStoreRemoveConfig::class,
                ObjectStoreRetrieveConfig::class, ObjectStoreRetrieveAllConfig::class,
                ObjectStoreRetrieveAllKeysConfig::class, ObjectStoreStoreConfig::class,
            ),
            configured.pipeline.map { it::class },
        )
        configured.pipeline.forEach { assertEquals(ObjectStoreRef.Default, assertIs<ObjectStoreOperationConfig>(it).objectStore) }
        assertEquals("#[vars.key]", assertIs<ObjectStoreContainsConfig>(configured.pipeline[1]).key)
        assertNull(assertIs<ObjectStoreRetrieveConfig>(configured.pipeline[3]).defaultValue)
        val store = assertIs<ObjectStoreStoreConfig>(configured.pipeline.last())
        assertNull(store.value)
        assertFalse(store.failIfPresent)
        assertTrue(store.failOnNullValue)
    }

    @Test
    fun `every operation accepts direct and named store references`() {
        val store = objectStore { name = "tokens" }
        val named = objectStore("tokens")
        val configured = flow("references") {
            objectStoreClear(store)
            objectStoreContains(store) { key = "a" }
            objectStoreRemove(store) { key = "a" }
            objectStoreRetrieve(store) { key = "a" }
            objectStoreRetrieveAll(store)
            objectStoreRetrieveAllKeys(store)
            objectStoreStore(store) { key = "a" }
            objectStoreClear(named)
            objectStoreContains(named) { key = "b" }
            objectStoreRemove(named) { key = "b" }
            objectStoreRetrieve(named) { key = "b" }
            objectStoreRetrieveAll(named)
            objectStoreRetrieveAllKeys(named)
            objectStoreStore(named) { key = "b" }
        }
        configured.pipeline.take(7).forEach {
            assertSame(store, assertIs<ObjectStoreRef.ByReference>(assertIs<ObjectStoreOperationConfig>(it).objectStore).config)
        }
        configured.pipeline.drop(7).forEach {
            assertEquals("tokens", assertIs<ObjectStoreRef.ByName>(assertIs<ObjectStoreOperationConfig>(it).objectStore).name)
        }
        assertSame(store, assertIs<ObjectStoreRef.ByReference>(objectStore(store)).config)
    }

    @Test
    fun `connector and store globals retain typed references and defaults`() {
        val connector = objectStoreConnector { name = "locks"; lockTimeout = Duration.ZERO }
        val defaults = objectStore { name = "default-settings" }
        assertNull(ObjectStoreConnectorConfig("unbounded").lockTimeout)
        assertEquals(ObjectStoreConnectorRef.Default, defaults.connector)
        assertTrue(defaults.persistent)
        assertNull(defaults.maxEntries)
        assertNull(defaults.entryTtl)
        assertEquals(Duration.ofMinutes(1), defaults.expirationInterval)
        val direct = objectStore { name = "direct"; this.connector = objectStoreConnector(connector) }
        assertSame(connector, assertIs<ObjectStoreConnectorRef.ByReference>(direct.connector).config)
        val named = objectStore {
            name = "named"
            this.connector = objectStoreConnector("locks")
            persistent = false
            maxEntries = 3
            entryTtl = Duration.ofSeconds(4)
            expirationInterval = Duration.ofSeconds(2)
        }
        assertEquals("locks", assertIs<ObjectStoreConnectorRef.ByName>(named.connector).name)
        assertFalse(named.persistent)
        assertEquals(3, named.maxEntries)
        assertEquals(Duration.ofSeconds(4), named.entryTtl)
        assertEquals(Duration.ofSeconds(2), named.expirationInterval)
    }

    @Test
    fun `read operation targets and expressions pass through unchanged`() {
        val target = TargetConfig("result", "#[payload.answer]")
        val configured = flow("targets") {
            objectStoreContains { key = "key"; targetConfig = target }
            objectStoreRetrieve { key = "key"; defaultValue = "#[vars.fallback]"; targetConfig = target }
            objectStoreRetrieveAll { targetConfig = target }
            objectStoreRetrieveAllKeys { targetConfig = target }
            objectStoreStore { key = "key"; value = "#[vars.value]"; failIfPresent = true; failOnNullValue = false }
        }
        assertEquals(target, assertIs<ObjectStoreContainsConfig>(configured.pipeline[0]).targetConfig)
        val retrieve = assertIs<ObjectStoreRetrieveConfig>(configured.pipeline[1])
        assertEquals(target, retrieve.targetConfig)
        assertEquals("#[vars.fallback]", retrieve.defaultValue)
        assertEquals(target, assertIs<ObjectStoreRetrieveAllConfig>(configured.pipeline[2]).targetConfig)
        assertEquals(target, assertIs<ObjectStoreRetrieveAllKeysConfig>(configured.pipeline[3]).targetConfig)
        val store = assertIs<ObjectStoreStoreConfig>(configured.pipeline[4])
        assertEquals("#[vars.value]", store.value)
        assertTrue(store.failIfPresent)
        assertFalse(store.failOnNullValue)
    }

    @Test
    fun `builders preserve documentation and explicit source metadata`() {
        val configured = flow("metadata") {
            objectStoreClear {
                docName = "Clear"
                docId = "clear-id"
                docDescription = "Remove entries"
                sourceFile = "source.xml"
                sourceLine = 12
                sourceClass = "Fixture"
            }
        }
        val clear = assertIs<ObjectStoreClearConfig>(configured.pipeline.single())
        assertEquals("Clear", clear.docName)
        assertEquals("clear-id", clear.docId)
        assertEquals("Remove entries", clear.docDescription)
        assertEquals("source.xml", clear.sourceFile)
        assertEquals(12, clear.sourceLine)
        assertEquals("Fixture", clear.sourceClass)
        val connector = objectStoreConnector { name = "locks"; docId = "connector-id"; sourceLine = 3 }
        assertEquals("connector-id", connector.docId)
        assertEquals(3, connector.sourceLine)
        val store = objectStore { name = "tokens"; docId = "store-id"; sourceLine = 4 }
        assertEquals("store-id", store.docId)
        assertEquals(4, store.sourceLine)
    }

    @Test
    fun `blank names and references fail before startup`() {
        listOf("", "  ").forEach { name ->
            assertFailsWith<IllegalArgumentException> { ObjectStoreConfig(name) }
            assertFailsWith<IllegalArgumentException> { ObjectStoreConnectorConfig(name) }
            assertFailsWith<IllegalArgumentException> { objectStore(name) }
            assertFailsWith<IllegalArgumentException> { objectStoreConnector(name) }
        }
        assertFailsWith<IllegalArgumentException> { objectStore {} }
        assertFailsWith<IllegalArgumentException> { objectStoreConnector {} }
    }

    @Test
    fun `capacity must be positive`() {
        listOf(0, -1).forEach { capacity ->
            assertFailsWith<IllegalArgumentException> { ObjectStoreConfig("bad", maxEntries = capacity) }
        }
    }

    @Test
    fun `nonpositive ttl and expiration interval disable their bounds`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { duration ->
            val store = ObjectStoreConfig("disabled", entryTtl = duration, expirationInterval = duration)
            assertEquals(duration, store.entryTtl)
            assertEquals(duration, store.expirationInterval)
        }
    }

    @Test
    fun `negative lock timeout and overflowing durations fail validation`() {
        assertFailsWith<IllegalArgumentException> { ObjectStoreConnectorConfig("bad", lockTimeout = Duration.ofNanos(-1)) }
        val overflow = Duration.ofSeconds(Long.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> { ObjectStoreConnectorConfig("bad", lockTimeout = overflow) }
        assertFailsWith<IllegalArgumentException> { ObjectStoreConfig("bad", entryTtl = overflow) }
        assertFailsWith<IllegalArgumentException> { ObjectStoreConfig("bad", expirationInterval = overflow) }
    }

    @Test
    fun `omitted keys fail while whitespace keys remain valid`() {
        assertFailsWith<IllegalArgumentException> { flow("invalid") { objectStoreContains {} } }
        assertFailsWith<IllegalArgumentException> { flow("invalid") { objectStoreRemove {} } }
        assertFailsWith<IllegalArgumentException> { flow("invalid") { objectStoreRetrieve {} } }
        assertFailsWith<IllegalArgumentException> { flow("invalid") { objectStoreStore {} } }
        val configured = flow("space") { objectStoreStore { key = " " } }
        assertEquals(" ", assertIs<ObjectStoreStoreConfig>(configured.pipeline.single()).key)
    }
}

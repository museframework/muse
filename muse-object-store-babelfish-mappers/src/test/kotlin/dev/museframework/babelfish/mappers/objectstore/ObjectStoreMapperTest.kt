package dev.museframework.babelfish.mappers.objectstore

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.objectstore.api.config.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

class ObjectStoreMapperTest {
    private val context = MappingContext(BabelfishRegistry(), Path.of("object-store.xml"))

    @Test
    fun `all seven operation mappers preserve defaults and typed references`() {
        val key = mapOf("key" to "#[vars.key]")
        assertEquals(ObjectStoreRef.Default, ObjectStoreClearMapper.map(element("clear"), context).objectStore)
        val contains = ObjectStoreContainsMapper.map(element("contains", key), context)
        assertEquals("#[vars.key]", contains.key)
        assertNull(contains.targetConfig)
        assertEquals("#[vars.key]", ObjectStoreRemoveMapper.map(element("remove", key), context).key)
        val retrieve = ObjectStoreRetrieveMapper.map(element("retrieve", key), context)
        assertNull(retrieve.defaultValue)
        assertNull(retrieve.targetConfig)
        assertEquals(ObjectStoreRef.Default, ObjectStoreRetrieveAllMapper.map(element("retrieve-all"), context).objectStore)
        assertEquals(ObjectStoreRef.Default, ObjectStoreRetrieveAllKeysMapper.map(element("retrieve-all-keys"), context).objectStore)
        val store = ObjectStoreStoreMapper.map(element("store", key + ("objectStore" to "named")), context)
        assertNull(store.value)
        assertFalse(store.failIfPresent)
        assertTrue(store.failOnNullValue)
        assertEquals(ObjectStoreRef.ByName("named"), store.objectStore)
    }

    @Test
    fun `maps targets flags metadata and complete CDATA child text`() {
        val text = "#[%dw 2.0\noutput application/json\n---\n{ amount: payload.amount }]"
        val store = ObjectStoreStoreMapper.map(
            element("store", mapOf("key" to "one", "doc:name" to "Save", "failIfPresent" to "true", "failOnNullValue" to "false"), listOf(element("value", text = text))), context,
        )
        assertEquals(text, store.value)
        assertTrue(store.failIfPresent)
        assertFalse(store.failOnNullValue)
        assertEquals("Save", store.docName)
        val retrieve = ObjectStoreRetrieveMapper.map(
            element("retrieve", mapOf("key" to "one", "target" to "found", "targetValue" to "#[payload.amount]"), listOf(element("default-value", text = text))), context,
        )
        assertEquals(text, retrieve.defaultValue)
        assertEquals(TargetConfig("found", "#[payload.amount]"), retrieve.targetConfig)
        assertEquals(TargetConfig("seen"), ObjectStoreContainsMapper.map(element("contains", mapOf("key" to "one", "target" to "seen")), context).targetConfig)
        assertEquals(TargetConfig("all"), ObjectStoreRetrieveAllMapper.map(element("retrieve-all", mapOf("target" to "all")), context).targetConfig)
        assertEquals(TargetConfig("keys"), ObjectStoreRetrieveAllKeysMapper.map(element("retrieve-all-keys", mapOf("target" to "keys")), context).targetConfig)
    }

    @Test
    fun `config mappers convert time units and preserve connector references`() {
        val connector = ObjectStoreConnectorConfigMapper.map(config("config", mapOf("name" to "locks", "lockTimeout" to "3", "lockTimeoutUnit" to "MILLISECONDS")), context)
        assertEquals(Duration.ofMillis(3), connector.lockTimeout)
        val store = ObjectStoreConfigMapper.map(config("object-store", mapOf("name" to "named", "config-ref" to "locks", "persistent" to "false", "maxEntries" to "25", "entryTtl" to "2", "entryTtlUnit" to "HOURS", "expirationInterval" to "4", "expirationIntervalUnit" to "SECONDS")), context)
        assertEquals(ObjectStoreConnectorRef.ByName("locks"), store.connector)
        assertFalse(store.persistent)
        assertEquals(25, store.maxEntries)
        assertEquals(Duration.ofHours(2), store.entryTtl)
        assertEquals(Duration.ofSeconds(4), store.expirationInterval)
        val defaults = ObjectStoreConfigMapper.map(config("object-store", mapOf("name" to "defaults")), context)
        assertTrue(defaults.persistent)
        assertNull(defaults.entryTtl)
        assertNull(defaults.maxEntries)
        assertEquals(Duration.ofMinutes(1), defaults.expirationInterval)
        assertNull(ObjectStoreConnectorConfigMapper.map(config("config", mapOf("name" to "defaults")), context).lockTimeout)
        assertEquals(Duration.ZERO, ObjectStoreConnectorConfigMapper.map(config("config", mapOf("name" to "zero", "lockTimeout" to "0")), context).lockTimeout)
        assertEquals(Duration.ofSeconds(-1), ObjectStoreConfigMapper.map(config("object-store", mapOf("name" to "disabled", "entryTtl" to "-1")), context).entryTtl)
    }

    @Test
    fun `invalid and unsupported inputs fail with element location`() {
        val badConfigs = listOf(
            config("config", mapOf("name" to "bad", "lockTimeout" to "-1")),
            config("config", mapOf("name" to "bad", "lockTimeout" to "abc")),
            config("config", mapOf("name" to "bad", "lockTimeoutUnit" to "FORTNIGHTS")),
            config("object-store", mapOf("name" to "bad", "maxEntries" to "0")),
            config("object-store", mapOf("name" to "bad", "persistent" to "yes")),
            config("object-store", mapOf("name" to "bad", "entryTtl" to "9223372036854775807", "entryTtlUnit" to "DAYS")),
            config("object-store", mapOf("name" to "bad", "partition" to "remote")),
            config("config", mapOf("name" to "bad"), listOf(element("connection", mapOf("provider" to "redis")))),
        )
        badConfigs.forEach { raw ->
            val failure = assertFailsWith<IllegalArgumentException> {
                if (raw.tag == "config") ObjectStoreConnectorConfigMapper.map(raw, context) else ObjectStoreConfigMapper.map(raw, context)
            }
            assertContains(failure.message.orEmpty(), "12:7")
            assertContains(failure.message.orEmpty(), "os:${raw.tag}")
        }
        listOf(
            element("store", mapOf("key" to "x", "failIfPresent" to "yes")),
            element("store", mapOf("key" to "x"), listOf(element("object-store"))),
            element("store", mapOf("key" to "x", "partition" to "remote")),
        ).forEach { raw -> assertContains(assertFailsWith<IllegalArgumentException> { ObjectStoreStoreMapper.map(raw, context) }.message.orEmpty(), "12:7") }
        assertFailsWith<IllegalArgumentException> { ObjectStoreContainsMapper.map(element("contains"), context) }
        assertFailsWith<IllegalArgumentException> { ObjectStoreClearMapper.map(element("clear", mapOf("target" to "discarded")), context) }
    }

    @Test
    fun `emitters generate every operation with typed references and escaped multiline strings`() {
        val ctx = CodegenContext("example")
        val named = ObjectStoreRef.ByName("named")
        val text = "#[%dw 2.0\n---\npayload]"
        assertContains(ObjectStoreClearCodeEmitter.emit(ObjectStoreClearConfig(), ctx), "objectStoreClear()")
        assertContains(ObjectStoreContainsCodeEmitter.emit(ObjectStoreContainsConfig("key", TargetConfig("seen", "#[payload]"), named), ctx), "objectStoreContains(objectStore(\"named\"))")
        assertContains(ObjectStoreRemoveCodeEmitter.emit(ObjectStoreRemoveConfig("key", named), ctx), "key = \"key\"")
        val retrieve = ObjectStoreRetrieveCodeEmitter.emit(ObjectStoreRetrieveConfig("key", text, TargetConfig("saved"), named), ctx)
        assertContains(retrieve, "defaultValue = \"#[%dw 2.0\\n---\\npayload]\"")
        assertContains(retrieve, "targetConfig = TargetConfig(\"saved\")")
        assertContains(ObjectStoreRetrieveAllCodeEmitter.emit(ObjectStoreRetrieveAllConfig(objectStore = named), ctx), "objectStoreRetrieveAll(objectStore(\"named\"))")
        assertContains(ObjectStoreRetrieveAllKeysCodeEmitter.emit(ObjectStoreRetrieveAllKeysConfig(objectStore = named), ctx), "objectStoreRetrieveAllKeys(objectStore(\"named\"))")
        val store = ObjectStoreStoreCodeEmitter.emit(ObjectStoreStoreConfig("key", text, true, false, named, docName = "Save"), ctx)
        assertContains(store, "value = \"#[%dw 2.0\\n---\\npayload]\"")
        assertContains(store, "failIfPresent = true")
        assertContains(store, "failOnNullValue = false")
        assertContains(store, "docName = \"Save\"")
        val defaults = ObjectStoreStoreCodeEmitter.emit(ObjectStoreStoreConfig("key"), ctx)
        assertFalse(defaults.contains("value ="))
        assertFalse(defaults.contains("failIfPresent"))
        assertFalse(defaults.contains("failOnNullValue"))
    }

    @Test
    fun `config emitters preserve duration precision and typed references`() {
        val ctx = CodegenContext("example")
        val locks = ObjectStoreConnectorConfig("locks", Duration.ofMillis(3))
        assertContains(ObjectStoreConnectorConfigCodeEmitter.emit(locks, ctx), "lockTimeout = Duration.ofNanos(3000000L)")
        val code = ObjectStoreConfigCodeEmitter.emit(ObjectStoreConfig("named", ObjectStoreConnectorRef.ByReference(locks), false, 20, Duration.ofSeconds(2), Duration.ZERO), ctx)
        assertContains(code, "connector = objectStoreConnector(\"locks\")")
        assertContains(code, "persistent = false")
        assertContains(code, "maxEntries = 20")
        assertContains(code, "entryTtl = Duration.ofNanos(2000000000L)")
        assertContains(code, "expirationInterval = Duration.ofNanos(0L)")
    }

    private fun element(tag: String, attrs: Map<String, String> = emptyMap(), children: List<RawElement> = emptyList(), text: String? = null) = RawElement("os", tag, attrs, children, SourceLocation(12, 7), text)
    private fun config(tag: String, attrs: Map<String, String>, children: List<RawElement> = emptyList()) = RawConfig(attrs["name"] ?: "missing", "os", tag, attrs, children, SourceLocation(12, 7))
}

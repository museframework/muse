package dev.museframework.babelfish.mappers.objectstore

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.objectstore.api.config.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.*

class ObjectStoreTranslationIT {
    @TempDir lateinit var temporary: Path

    @Test
    fun `translates all operations and emits typed named and default stores independent of declaration order`() {
        val provider = ObjectStoreMapperProvider()
        val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
        val result = MuseBabelfish.translateXmlToMuseKotlinDsl(Path.of("src/test/resources/mule/object-store/all-operations.xml"), registry)
        val named = assertIs<ObjectStoreConfig>(result.usedConfigs.getValue("named"))
        assertEquals(ObjectStoreConnectorRef.ByName("locks"), named.connector)
        assertEquals(Duration.ofMinutes(2), named.entryTtl)
        assertEquals(Duration.ofMillis(50), assertIs<ObjectStoreConnectorConfig>(result.usedConfigs.getValue("locks")).lockTimeout)
        val operations = assertIs<MuseFlow.Flow>(result.flows.single()).pipeline
        assertEquals(7, operations.size)
        assertEquals(ObjectStoreRef.Default, assertIs<ObjectStoreClearConfig>(operations[0]).objectStore)
        val store = assertIs<ObjectStoreStoreConfig>(operations[1])
        assertEquals(ObjectStoreRef.ByName("named"), store.objectStore)
        assertEquals("#[%dw 2.0\noutput application/json\n---\npayload]", store.value)
        assertIs<ObjectStoreContainsConfig>(operations[2])
        val retrieve = assertIs<ObjectStoreRetrieveConfig>(operations[3])
        assertEquals("#[%dw 2.0\n---\n{ fallback: true }]", retrieve.defaultValue)
        assertIs<ObjectStoreRetrieveAllConfig>(operations[4])
        assertIs<ObjectStoreRetrieveAllKeysConfig>(operations[5])
        assertIs<ObjectStoreRemoveConfig>(operations[6])
        val code = KotlinCodeEmitter().apply { provider.registerEmitters(this) }.emit(result, "example")
        listOf("objectStoreClear()", "objectStoreStore(objectStore(\"named\"))", "objectStoreContains", "objectStoreRetrieve", "objectStoreRetrieveAll", "objectStoreRetrieveAllKeys", "objectStoreRemove").forEach { assertContains(code, it) }
        assertContains(code, "connector = objectStoreConnector(\"locks\")")
        assertContains(code, "\\noutput application/json\\n")
        assertContains(code, "targetConfig = TargetConfig(\"found\", \"#[payload]\")")
    }

    @Test
    fun `invalid global configurations propagate their XML location through full translation`() {
        listOf(
            "<os:config name=\"bad\" lockTimeout=\"-1\"/>",
            "<os:object-store name=\"bad\" maxEntries=\"0\"/>",
            "<os:object-store name=\"bad\" partition=\"cloud\"/>",
            "<os:config name=\"bad\"><os:connection provider=\"redis\"/></os:config>",
        ).forEach { body ->
            val path = temporary.resolve("invalid.xml").apply { writeText(document(body)) }
            val failure = assertFailsWith<IllegalArgumentException> { translate(path) }
            assertContains(failure.message.orEmpty(), "invalid.xml")
            assertContains(failure.message.orEmpty(), "line")
            assertContains(failure.message.orEmpty(), "os:")
        }
    }

    @Test
    fun `private stores and unknown object store tags fail with source location`() {
        listOf(
            "<flow name=\"test\"><os:store key=\"one\"><os:object-store/></os:store></flow>",
            "<flow name=\"test\"><os:unknown/></flow>",
        ).forEach { body ->
            val path = temporary.resolve("unsupported.xml").apply { writeText(document(body)) }
            val failure = assertFails { translate(path) }
            assertContains(failure.message.orEmpty(), "line")
        }
    }

    @Test
    fun `duplicate named globals fail before the first declaration can be lost`() {
        listOf(
            "<os:object-store name=\"duplicate\"/><os:object-store name=\"duplicate\" persistent=\"false\"/>",
            "<os:config name=\"duplicate\"/><os:config name=\"duplicate\" lockTimeout=\"1\"/>",
        ).forEach { body ->
            val path = temporary.resolve("duplicates.xml").apply { writeText(document(body)) }
            val failure = assertFailsWith<IllegalArgumentException> { translate(path) }
            assertContains(failure.message.orEmpty(), "duplicate")
            assertContains(failure.message.orEmpty(), "line")
        }
    }

    @Test
    fun `object store errors remain typed through translation and emission`() {
        val path = temporary.resolve("errors.xml").apply {
            writeText(document("""<flow name="errors"><os:retrieve key="missing"/><error-handler><on-error-continue type="OS:KEY_NOT_FOUND"><os:clear/></on-error-continue></error-handler></flow>"""))
        }
        val result = translate(path)
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val handler = assertIs<ErrorHandlerStrategy.OnErrorContinue>(flow.errorHandler!!.strategies.single())
        assertEquals(ErrorType("OS", "KEY_NOT_FOUND"), handler.errorType)
        assertIs<ObjectStoreClearConfig>(handler.pipeline.single())
        val code = KotlinCodeEmitter().apply { ObjectStoreMapperProvider().registerEmitters(this) }.emit(result, "example")
        assertContains(code, "ErrorType(\"OS\", \"KEY_NOT_FOUND\")")
    }

    @Test
    fun `CDATA expressions retain leading trailing and internal whitespace exactly`() {
        val expression = "\n  #[%dw 2.0\n---\npayload]  \n"
        val path = temporary.resolve("whitespace.xml").apply {
            writeText(document("""<flow name="whitespace"><os:store key="one"><os:value><![CDATA[$expression]]></os:value></os:store><os:retrieve key="two"><os:default-value><![CDATA[$expression]]></os:default-value></os:retrieve></flow>"""))
        }
        val result = translate(path)
        val operations = assertIs<MuseFlow.Flow>(result.flows.single()).pipeline
        assertEquals(expression, assertIs<ObjectStoreStoreConfig>(operations[0]).value)
        assertEquals(expression, assertIs<ObjectStoreRetrieveConfig>(operations[1]).defaultValue)
        val code = KotlinCodeEmitter().apply { ObjectStoreMapperProvider().registerEmitters(this) }.emit(result, "example")
        assertContains(code, "\\n  #[%dw 2.0\\n---\\npayload]  \\n")
    }

    private fun translate(path: Path) = MuseBabelfish.translateXmlToMuseKotlinDsl(path, BabelfishRegistry().apply { ObjectStoreMapperProvider().registerMappings(this) })
    private fun document(body: String) = """<mule xmlns="http://www.mulesoft.org/schema/mule/core" xmlns:os="http://www.mulesoft.org/schema/mule/os">$body</mule>"""
}

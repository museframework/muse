package dev.museframework.plugin.core.translation

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.ConfigurationClassSpec
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.exception.BabelfishTranslationException
import dev.museframework.babelfish.exception.UnmappedElementException
import dev.museframework.babelfish.mappers.core.CoreMapperProvider
import dev.museframework.babelfish.mappers.core.SetPayloadMapper
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import dev.museframework.plugin.core.runtime.quarkus.QuarkusRuntimeStrategy
import dev.museframework.plugin.core.runtime.spring.SpringBootRuntimeStrategy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranslatorFacadeTest {
    @Test
    fun `translateAll returns empty list when sourceDirectory does not exist`() {
        val facade = TranslatorFacade()
        val result = facade.translateAll(java.io.File("/non/existent/path"), "com.example")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `translateAll returns empty list for directory with no XML files`(@TempDir dir: Path) {
        dir.resolve("readme.txt").toFile().writeText("hello")
        val facade = TranslatorFacade()
        val result = facade.translateAll(dir.toFile(), "com.example")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `generated file path and declaration use the same name`(@TempDir dir: Path) {
        val facade = TranslatorFacade(TranslatorRegistry.of(emptyList()))
        dir.resolve("3-order.API__flow.XML").toFile().writeText("<mule/>")
        val file = facade.translateAll(dir.toFile(), "com.example").single().generatedFiles.single()
        assertEquals("com/example/_3OrderAPIFlowConfig.kt", file.relativePath)
        assertContains(file.content, "class _3OrderAPIFlowConfig")
    }

    @Test
    fun `two input files with the same output path report both sources`(@TempDir dir: Path) {
        val facade = TranslatorFacade(TranslatorRegistry.of(emptyList()))
        dir.resolve("customer-order.xml").toFile().writeText("<mule/>")
        dir.resolve("customer_order.xml").toFile().writeText("<mule/>")
        val error = assertFailsWith<MuseTranslationException> { facade.translateAll(dir.toFile(), "com.example") }
        assertContains(error.message!!, "customer-order.xml")
        assertContains(error.message!!, "customer_order.xml")
        assertContains(error.message!!, "com/example/CustomerOrderConfig.kt")
    }

    @Test
    fun `core-only XML generation succeeds with only core provider`(@TempDir dir: Path) {
        val facade = TranslatorFacade(registry = TranslatorRegistry.of(listOf(CoreMapperProvider())))
        val file = writeCoreFlow(dir, "core-flow.xml")

        val results = facade.translateAll(dir.toFile(), "com.example")

        assertEquals(1, results.size)
        assertEquals(1, results[0].generatedFiles.size)
        assertContains(results[0].generatedFiles[0].content, "setPayload")
        assertTrue(results[0].warnings.isEmpty())
    }

    @Test
    fun `a mapping-only provider cannot produce incomplete Kotlin in lenient mode`(@TempDir dir: Path) {
        writeCoreFlow(dir, "mapped.xml")
        val facade = TranslatorFacade(TranslatorRegistry.of(listOf(DuplicateCoreMappingProvider())))
        val failure = assertFailsWith<MuseTranslationException> {
            facade.translateAll(dir.toFile(), "com.example", lenient = true)
        }
        assertContains(failure.message!!, "mapped.xml")
        assertContains(failure.message!!, "hello-flow")
        assertContains(failure.message!!, "SetPayloadConfig")
    }

    @Test
    fun `strict HTTP XML fails without HTTP provider and mentions plugin dependencies`(@TempDir dir: Path) {
        val facade = TranslatorFacade(registry = TranslatorRegistry.of(listOf(CoreMapperProvider())))
        writeHttpListenerFlow(dir, "http-flow.xml")

        val exception =
            assertFailsWith<MuseTranslationException> {
                facade.translateAll(dir.toFile(), "com.example")
            }

        assertContains(exception.message!!, "Failed to translate")
        assertContains(exception.message!!, "http:listener")
        assertContains(exception.message!!, "<dependencies>")
        assertContains(exception.message!!, "ordinary project dependency")
        assertContains(exception.message!!, "1 distinct unmapped occurrence")
        assertTrue(
            exception.cause != null,
            "Expected strict failure to wrap the original Babelfish exception, but cause was null",
        )
    }

    @Test
    fun `lenient HTTP produces a warning and no generated Kotlin file`(@TempDir dir: Path) {
        val facade = TranslatorFacade(registry = TranslatorRegistry.of(listOf(CoreMapperProvider())))
        writeHttpListenerFlow(dir, "http-flow.xml")

        val results = facade.translateAll(dir.toFile(), "com.example", lenient = true)

        assertEquals(1, results.size)
        assertTrue(results[0].generatedFiles.isEmpty())
        assertEquals(1, results[0].warnings.size)
        assertContains(results[0].warnings[0], "Skipped generation")
        assertContains(results[0].warnings[0], "http:listener")
        assertContains(results[0].warnings[0], "<dependencies>")
    }

    @Test
    fun `missing mapper formatter deduplicates and deterministically orders every occurrence`(@TempDir dir: Path) {
        val sourceFile = dir.resolve("multi-flow.xml")
        val errors =
            listOf(
                unmapped(sourceFile, "db", "select", line = 20, column = 9),
                unmapped(sourceFile, "http", "listener", line = 10, column = 7),
                unmapped(sourceFile, "http", "listener-config", line = 4, column = 3),
                unmapped(sourceFile, "http", "listener", line = 12, column = 7),
                unmapped(
                    dir.resolve("nested").resolve("..").resolve("multi-flow.xml"),
                    "http",
                    "listener-config",
                    line = 4,
                    column = 3,
                ),
            )

        val message = formatMissingMapperDiagnostic(sourceFile.toFile(), errors, lenient = false)
        val reversedMessage =
            formatMissingMapperDiagnostic(sourceFile.toFile(), errors.reversed(), lenient = false)

        assertEquals(message, reversedMessage)
        assertEquals(
            """
            Failed to translate 'multi-flow.xml': 4 distinct unmapped occurrences
            1. no mapper registered for 'http:listener-config' at line 4, col 3
            2. no mapper registered for 'http:listener' at line 10, col 7
            3. no mapper registered for 'http:listener' at line 12, col 7
            4. no mapper registered for 'db:select' at line 20, col 9
            Add the appropriate mapper artifact under the Maven plugin's <dependencies> element; an ordinary project dependency is not visible to the plugin realm.
            """.trimIndent(),
            message,
        )
        assertEquals(1, message.occurrencesOf("<dependencies>"))

        val singleMessage =
            formatMissingMapperDiagnostic(sourceFile.toFile(), listOf(errors.first()), lenient = false)
        assertContains(singleMessage, "1 distinct unmapped occurrence")
    }

    @Test
    fun `strict and lenient modes share one complete multi-element diagnostic`(@TempDir dir: Path) {
        val facade = TranslatorFacade(registry = TranslatorRegistry.of(listOf(CoreMapperProvider())))
        writeHttpListenerAndConfigFlow(dir, "http-flow.xml")

        val strictException =
            assertFailsWith<MuseTranslationException> {
                facade.translateAll(dir.toFile(), "com.example")
            }
        val lenientResult = facade.translateAll(dir.toFile(), "com.example", lenient = true).single()

        val strictMessage = strictException.message!!
        val lenientMessage = lenientResult.warnings.single()
        assertContains(strictMessage, "Failed to translate 'http-flow.xml': 2 distinct unmapped occurrences")
        assertContains(lenientMessage, "Skipped generation for 'http-flow.xml': 2 distinct unmapped occurrences")
        assertEquals(strictMessage.substringAfter('\n'), lenientMessage.substringAfter('\n'))
        assertTrue(strictException is MissingMapperTranslationException)
        assertTrue(strictException.cause is BabelfishTranslationException)
        assertTrue(lenientResult.generatedFiles.isEmpty())
        assertEquals(1, strictMessage.occurrencesOf("no mapper registered for 'http:listener-config'"))
        assertEquals(1, strictMessage.occurrencesOf("no mapper registered for 'http:listener'"))
        assertEquals(1, strictMessage.occurrencesOf("<dependencies>"))
        assertTrue(
            strictMessage.indexOf("'http:listener-config'") < strictMessage.indexOf("'http:listener'"),
            "Expected listener-config to appear before listener in the diagnostic:\n$strictMessage",
        )
    }

    @Test
    fun `provider registration collision remains fatal in lenient mode`(@TempDir dir: Path) {
        val facade =
            TranslatorFacade(
                registry =
                    TranslatorRegistry.of(
                        listOf(CoreMapperProvider(), DuplicateCoreMappingProvider()),
                    ),
            )
        writeCoreFlow(dir, "core-flow.xml")

        val exception =
            assertFailsWith<MuseTranslationException> {
                facade.translateAll(dir.toFile(), "com.example", lenient = true)
            }

        assertContains(exception.message!!, "Babelfish registration collision")
        assertContains(exception.message!!, "element mapper key 'set-payload'")
        assertContains(exception.message!!, "owner id='core'")
        assertContains(exception.message!!, "owner id='duplicate-core'")
    }

    @Test
    fun `nativeBuild reaches provider emitter registration`(@TempDir dir: Path) {
        val nativeRecorder = NativeBuildRecordingProvider()
        val facade =
            TranslatorFacade(
                registry = TranslatorRegistry.of(listOf(CoreMapperProvider(), nativeRecorder)),
            )
        writeCoreFlow(dir, "core-flow.xml")

        facade.translateAll(
            dir.toFile(),
            "com.example",
            configurationClassSpec = SpringBootRuntimeStrategy().configurationClassSpec(),
            nativeBuild = true,
        )

        assertEquals(listOf(true), nativeRecorder.received)
    }

    @Test
    fun `standard generation emits the discovery annotations for each runtime`(@TempDir dir: Path) {
        val facade = TranslatorFacade(TranslatorRegistry.of(listOf(CoreMapperProvider())))
        writeCoreFlow(dir, "core-flow.xml")

        val declarations = listOf(
            ConfigurationClassSpec.Plain to listOf("dev.museframework.dsl.annotation.MuseConfig"),
            SpringBootRuntimeStrategy().configurationClassSpec() to
                listOf("dev.museframework.springboot.MuseConfigComponent"),
            QuarkusRuntimeStrategy().configurationClassSpec() to
                listOf("dev.museframework.dsl.annotation.MuseConfig", "jakarta.enterprise.context.ApplicationScoped"),
        )
        for ((spec, annotations) in declarations) {
            val content = facade.translateAll(dir.toFile(), "com.example", spec).single().generatedFiles.single().content
            assertEquals(annotations.map { "@" + it.substringAfterLast('.') }, content.lines().filter { it.startsWith('@') })
            annotations.forEach { assertImport(content, it) }
            assertContains(content, "class CoreFlowConfig : MuseConfigDefinition()")
            assertFalse(content.contains("MuseConfigProvider"))
        }
    }

    @Test
    fun `native generation exposes ordered configs and flows and inherits empty defaults`(@TempDir dir: Path) {
        val facade = TranslatorFacade(TranslatorRegistry.of(listOf(CoreMapperProvider())))
        dir.resolve("empty.xml").toFile().writeText("""<mule xmlns="http://www.mulesoft.org/schema/mule/core"/>""")
        dir.resolve("ordered.xml").toFile().writeText(
            """
            <mule xmlns="http://www.mulesoft.org/schema/mule/core">
                <configuration-properties name="second-config" file="second.properties"/>
                <configuration-properties name="first-config" file="first.properties"/>
                <flow name="second-flow"><flow-ref name="first-flow"/></flow>
                <sub-flow name="first-flow"><set-payload value="done"/></sub-flow>
            </mule>
            """.trimIndent()
        )

        val declarations = listOf(
            Triple(
                SpringBootRuntimeStrategy().configurationClassSpec(),
                "dev.museframework.springboot",
                "org.springframework.stereotype.Component",
            ),
            Triple(
                QuarkusRuntimeStrategy().configurationClassSpec(),
                "dev.museframework.quarkus",
                "jakarta.enterprise.context.ApplicationScoped",
            ),
        )
        for ((spec, providerPackage, hostAnnotation) in declarations) {
            val outputs = facade.translateAll(dir.toFile(), "com.example", spec, nativeBuild = true)
                .associate { it.sourceFile.name to it.generatedFiles.single().content }
            val empty = outputs.getValue("empty.xml")
            val ordered = outputs.getValue("ordered.xml")
            for (content in outputs.values) {
                assertImport(content, "dev.museframework.dsl.annotation.MuseConfig")
                assertImport(content, hostAnnotation)
                assertImport(content, "$providerPackage.MuseConfigProvider")
                assertEquals(
                    listOf("@MuseConfig", "@" + hostAnnotation.substringAfterLast('.')),
                    content.lines().filter { it.startsWith('@') },
                )
                assertFalse(content.contains("MuseConfigDefinition"))
            }
            assertContains(empty, "class EmptyConfig : MuseConfigProvider")
            assertFalse(empty.contains("override fun"))
            assertContains(ordered, "class OrderedConfig : MuseConfigProvider")
            val propertyNames = Regex("""\bval (\w+):""")
                .findAll(ordered).map { it.groupValues[1] }.toList()
            assertEquals(listOf("secondConfig", "firstConfig", "secondFlow", "firstFlow"), propertyNames)
            assertContains(ordered, "flows(): List<MuseFlow> = listOf(secondFlow, firstFlow)")
            assertContains(ordered, "configs(): List<MuseConfiguration> = listOf(secondConfig, firstConfig)")
        }
    }

    private fun writeCoreFlow(dir: Path, name: String): java.io.File {
        val file = dir.resolve(name).toFile()
        file.writeText(
            """
            <mule xmlns="http://www.mulesoft.org/schema/mule/core">
              <flow name="hello-flow">
                <set-payload value="Hello"/>
              </flow>
            </mule>
            """.trimIndent(),
        )
        return file
    }

    private fun writeHttpListenerFlow(dir: Path, name: String) {
        val file = dir.resolve(name).toFile()
        file.writeText(
            """
            <mule xmlns="http://www.mulesoft.org/schema/mule/core"
                  xmlns:http="http://www.mulesoft.org/schema/mule/http">
              <flow name="http-flow">
                <http:listener config-ref="HTTP_Listener_config" path="/test"/>
                <set-payload value="Hello"/>
              </flow>
            </mule>
            """.trimIndent(),
        )
    }

    private fun writeHttpListenerAndConfigFlow(dir: Path, name: String) {
        val file = dir.resolve(name).toFile()
        file.writeText(
            """
            <mule xmlns="http://www.mulesoft.org/schema/mule/core"
                  xmlns:http="http://www.mulesoft.org/schema/mule/http">
              <http:listener-config name="HTTP_Listener_config"/>
              <flow name="http-flow">
                <http:listener config-ref="HTTP_Listener_config" path="/test"/>
                <set-payload value="Hello"/>
              </flow>
            </mule>
            """.trimIndent(),
        )
    }

    private fun unmapped(
        sourceFile: Path,
        namespace: String,
        tag: String,
        line: Int,
        column: Int,
    ) =
        UnmappedElementException(
            namespace = namespace,
            tag = tag,
            location = SourceLocation(line, column),
            sourceFile = sourceFile,
        )

    private fun String.occurrencesOf(fragment: String): Int =
        Regex(Regex.escape(fragment)).findAll(this).count()

    private class NativeBuildRecordingProvider : BabelfishMapperProvider {
        override val id: String = "native-recorder"
        val received = mutableListOf<Boolean>()

        override fun registerMappings(registry: BabelfishRegistry) {}

        override fun registerEmitters(
            emitter: KotlinCodeEmitter,
            nativeBuild: Boolean,
        ) {
            received.add(nativeBuild)
        }
    }

    private class DuplicateCoreMappingProvider : BabelfishMapperProvider {
        override val id: String = "duplicate-core"

        override fun registerMappings(registry: BabelfishRegistry) {
            registry.registerElement("", "set-payload", SetPayloadMapper)
        }

        override fun registerEmitters(
            emitter: KotlinCodeEmitter,
            nativeBuild: Boolean,
        ) {}
    }

    private fun assertImport(content: String, importedType: String) {
        assertTrue(
            content.lines().any { line ->
                line.startsWith("import ") && line.replace("`", "") == "import $importedType"
            },
            "Expected import $importedType in generated Kotlin",
        )
    }
}

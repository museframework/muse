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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranslatorFacadeTest {
    private val nativeConfigurationSpec =
        ConfigurationClassSpec(
            definition =
                ConfigurationClassSpec.Declaration(
                    annotationTypes = listOf("dev.museframework.springboot.MuseConfigComponent"),
                ),
            native =
                ConfigurationClassSpec.Declaration(
                    annotationTypes =
                        listOf(
                            "dev.museframework.dsl.annotation.MuseConfig",
                            "org.springframework.stereotype.Component",
                        ),
                    providerInterface = "dev.museframework.springboot.MuseConfigProvider",
                ),
        )

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
    fun `deriveClassName converts kebab-case to PascalCase with Config suffix`() {
        val facade = TranslatorFacade()
        assertEquals("MyOrderFlowConfig", facade.deriveClassName(java.io.File("my-order-flow.xml")))
        assertEquals("SimpleConfig", facade.deriveClassName(java.io.File("simple.xml")))
        assertEquals("HttpListenerConfig", facade.deriveClassName(java.io.File("http-listener.xml")))
        assertEquals("HttpListenerConfig", facade.deriveClassName(java.io.File("http_listener.xml")))
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
            configurationClassSpec = nativeConfigurationSpec,
            nativeBuild = true,
        )

        assertEquals(listOf(true), nativeRecorder.received)
    }

    @Test
    fun `nativeBuild emits reflection-free runtime provider output`(@TempDir dir: Path) {
        val facade = TranslatorFacade(registry = TranslatorRegistry.of(listOf(CoreMapperProvider())))
        writeCoreFlow(dir, "core-flow.xml")

        val results =
            facade.translateAll(
                dir.toFile(),
                "com.example",
                configurationClassSpec = nativeConfigurationSpec,
                nativeBuild = true,
            )

        assertEquals(1, results.size)
        val content = results[0].generatedFiles.single().content
        assertContains(content, "class CoreFlowConfig : MuseConfigProvider")
        assertContains(content, "override fun flows(): List<MuseFlow>")
        assertTrue(!content.contains("MuseConfigDefinition"))
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
}

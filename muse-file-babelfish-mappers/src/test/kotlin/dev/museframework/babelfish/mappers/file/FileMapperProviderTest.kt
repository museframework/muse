package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import dev.museframework.file.api.config.FileConnectionConfig
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileMapperProviderTest {
    @Test
    fun `provider id is file`() {
        assertEquals("file", FileMapperProvider().id)
    }

    @Test
    fun `provider has a public no-argument constructor`() {
        val constructor = FileMapperProvider::class.java.getConstructor()

        assertTrue(Modifier.isPublic(constructor.modifiers))
        assertEquals(FileMapperProvider::class.java, constructor.newInstance()::class.java)
    }

    @Test
    fun `provider is discovered exactly once by its classloader`() {
        val discovered =
            ServiceLoader
                .load(BabelfishMapperProvider::class.java, FileMapperProvider::class.java.classLoader)
                .toList()
        val implementations = discovered.filter { it::class == FileMapperProvider::class }

        assertEquals(1, implementations.size)
        assertEquals("file", implementations.single().id)
        assertEquals(1, discovered.count { it.id == "file" })
    }

    @Test
    fun `provider registers file mappings on independent registries`() {
        repeat(2) {
            val registry = BabelfishRegistry().apply { FileMapperProvider().registerMappings(this) }
            val rawConfig = fileConfig()
            val mapper = registry.resolveConfig(rawConfig, SOURCE_FILE)
            val config = mapper.map(rawConfig, MappingContext(registry, SOURCE_FILE))

            assertIs<FileConnectionConfig>(config)
        }
    }

    @Test
    fun `provider registers file emitters on independent emitters`() {
        repeat(2) {
            val registry = BabelfishRegistry().apply { FileMapperProvider().registerMappings(this) }
            val rawConfig = fileConfig()
            val mapper = registry.resolveConfig(rawConfig, SOURCE_FILE)
            val config = mapper.map(rawConfig, MappingContext(registry, SOURCE_FILE))
            val translationResult =
                TranslationResult(
                    sourceFile = SOURCE_FILE,
                    flows = emptyList(),
                    usedConfigs = mapOf("File_Config" to config),
                )
            val emitter = KotlinCodeEmitter().apply { FileMapperProvider().registerEmitters(this) }

            assertContains(emitter.emit(translationResult, "com.example"), "fileConnection")
        }
    }

    private fun fileConfig(): RawConfig =
        RawConfig(
            id = "internal-uuid",
            namespace = "file",
            tag = "config",
            attrs = mapOf("name" to "File_Config", "doc:name" to "File Config"),
            children =
                listOf(
                    RawElement(
                        namespace = "file",
                        tag = "connection",
                        attrs = mapOf("workingDir" to "/tmp"),
                        children = emptyList(),
                        location = SourceLocation(1, 1),
                    ),
                ),
            location = SourceLocation(1, 1),
        )

    private companion object {
        val SOURCE_FILE: Path = Path.of("test.xml")
    }
}

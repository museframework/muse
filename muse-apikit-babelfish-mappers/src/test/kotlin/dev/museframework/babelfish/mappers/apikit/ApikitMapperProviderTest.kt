package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApikitMapperProviderTest {
    @Test
    fun `provider id is apikit`() {
        assertEquals("apikit", ApikitMapperProvider().id)
    }

    @Test
    fun `provider has a public no-argument constructor`() {
        val constructor = ApikitMapperProvider::class.java.getConstructor()

        assertTrue(Modifier.isPublic(constructor.modifiers))
        assertEquals(ApikitMapperProvider::class.java, constructor.newInstance()::class.java)
    }

    @Test
    fun `provider is discovered exactly once by its classloader`() {
        val discovered =
            ServiceLoader
                .load(BabelfishMapperProvider::class.java, ApikitMapperProvider::class.java.classLoader)
                .toList()
        val implementations = discovered.filter { it::class == ApikitMapperProvider::class }

        assertEquals(1, implementations.size)
        assertEquals("apikit", implementations.single().id)
        assertEquals(1, discovered.count { it.id == "apikit" })
    }

    @Test
    fun `provider registers apikit mappings on independent registries`() {
        repeat(2) {
            val registry = BabelfishRegistry().apply { ApikitMapperProvider().registerMappings(this) }
            val rawConfig = apikitConfig()
            val mapper = registry.resolveConfig(rawConfig, SOURCE_FILE)
            val config = mapper.map(rawConfig, MappingContext(registry, SOURCE_FILE))

            assertIs<ApikitConfig>(config)
        }
    }

    @Test
    fun `provider registers apikit emitters on independent emitters`() {
        repeat(2) {
            val registry = BabelfishRegistry().apply { ApikitMapperProvider().registerMappings(this) }
            val rawConfig = apikitConfig()
            val mapper = registry.resolveConfig(rawConfig, SOURCE_FILE)
            val config = mapper.map(rawConfig, MappingContext(registry, SOURCE_FILE))
            val translationResult =
                TranslationResult(
                    sourceFile = SOURCE_FILE,
                    flows = emptyList(),
                    usedConfigs = mapOf("api-spec-config" to config),
                )
            val emitter = KotlinCodeEmitter().apply { ApikitMapperProvider().registerEmitters(this) }

            assertContains(emitter.emit(translationResult, "com.example"), "apikitConfig {")
        }
    }

    private fun apikitConfig(): RawConfig =
        RawConfig(
            id = "internal-uuid",
            namespace = "apikit",
            tag = "config",
            attrs = mapOf("name" to "api-spec-config", "api" to "api/api-spec.raml"),
            children = emptyList(),
            location = SourceLocation(1, 1),
        )

    private companion object {
        val SOURCE_FILE: Path = Path.of("test.xml")
    }
}

package dev.museframework.babelfish.mappers.http

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpMapperProviderTest {
    @Test
    fun `provider id is http`() {
        assertEquals("http", HttpMapperProvider().id)
    }

    @Test
    fun `provider has a public no-argument constructor`() {
        val constructor = HttpMapperProvider::class.java.getConstructor()

        assertTrue(Modifier.isPublic(constructor.modifiers))
        assertEquals(HttpMapperProvider::class.java, constructor.newInstance()::class.java)
    }

    @Test
    fun `provider is discovered exactly once by its classloader`() {
        val discovered =
            ServiceLoader
                .load(BabelfishMapperProvider::class.java, HttpMapperProvider::class.java.classLoader)
                .toList()
        val implementations = discovered.filter { it::class == HttpMapperProvider::class }

        assertEquals(1, implementations.size)
        assertEquals("http", implementations.single().id)
        assertEquals(1, discovered.count { it.id == "http" })
    }

    @Test
    fun `provider registers http mappings on independent registries`() {
        repeat(2) {
            val registry = BabelfishRegistry().apply { HttpMapperProvider().registerMappings(this) }
            val rawConfig = listenerConfig()
            val mapper = registry.resolveConfig(rawConfig, SOURCE_FILE)
            val config = mapper.map(rawConfig, MappingContext(registry, SOURCE_FILE))

            assertIs<HttpListenerConnectionConfig>(config)
        }
    }

    @Test
    fun `provider registers http emitters on independent emitters`() {
        repeat(2) {
            val registry = BabelfishRegistry().apply { HttpMapperProvider().registerMappings(this) }
            val rawConfig = listenerConfig()
            val mapper = registry.resolveConfig(rawConfig, SOURCE_FILE)
            val config = mapper.map(rawConfig, MappingContext(registry, SOURCE_FILE))
            val translationResult =
                TranslationResult(
                    sourceFile = SOURCE_FILE,
                    flows = emptyList(),
                    usedConfigs = mapOf("HTTP_Listener_config" to config),
                )
            val emitter = KotlinCodeEmitter().apply { HttpMapperProvider().registerEmitters(this) }

            assertContains(emitter.emit(translationResult, "com.example"), "httpListenerConnection")
        }
    }

    private fun listenerConfig(): RawConfig =
        RawConfig(
            id = "internal-uuid",
            namespace = "http",
            tag = "listener-config",
            attrs = mapOf("name" to "HTTP_Listener_config", "doc:name" to "HTTP Listener config"),
            children =
                listOf(
                    RawElement(
                        namespace = "http",
                        tag = "listener-connection",
                        attrs = mapOf("host" to "0.0.0.0", "port" to "8081"),
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

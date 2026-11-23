package dev.museframework.babelfish.mappers.http

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
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
        val provider = HttpMapperProvider()
        val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
        val translation = MuseBabelfish.translateXmlToMuseKotlinDsl(
            Path.of("src/test/resources/mule/http/http-listener-request.xml"),
            registry,
        )

        repeat(2) {
            val emitter = KotlinCodeEmitter().apply { provider.registerEmitters(this) }
            val code = emitter.emit(translation, "com.example")

            assertContains(code, "httpListenerConnection(\"HTTP_Listener_config\")")
            assertContains(code, "httpListener(\"HTTP_Listener_config\")")
            assertContains(code, "httpRequestConnection {")
            assertContains(code, "httpRequest(httpRequestConnection(\"HTTP_Request_config\"))")
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

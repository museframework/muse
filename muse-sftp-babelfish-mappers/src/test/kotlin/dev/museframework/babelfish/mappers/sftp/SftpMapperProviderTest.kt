package dev.museframework.babelfish.mappers.sftp

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import dev.museframework.sftp.api.config.SftpConnectionConfig
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SftpMapperProviderTest {
    @Test
    fun `connection property placeholders survive mapping and emission including port`() {
        val registry = BabelfishRegistry().apply { SftpMapperProvider().registerMappings(this) }
        val raw = sftpConfig().let { config ->
            config.copy(children = config.children.map { connection ->
                connection.copy(attrs = connection.attrs + mapOf(
                    "host" to "\${sftp.host}",
                    "port" to "\${sftp.origin.port}",
                    "username" to "\${sftp.user.name}",
                    "password" to "\${sftp.password}",
                ))
            })
        }
        val config = registry.resolveConfig(raw, SOURCE_FILE).map(raw, MappingContext(registry, SOURCE_FILE))
        val emitter = KotlinCodeEmitter().apply { SftpMapperProvider().registerEmitters(this) }
        val code = emitter.emit(TranslationResult(
            sourceFile = SOURCE_FILE,
            flows = emptyList(),
            usedConfigs = mapOf("Sftp_Config" to config),
        ), "com.example")

        assertContains(code, "portExpression = \"\\\${sftp.origin.port}\"")
        assertContains(code, "host = \"\\\${sftp.host}\"")
    }

    @Test
    fun `provider id is sftp`() {
        assertEquals("sftp", SftpMapperProvider().id)
    }

    @Test
    fun `provider has a public no-argument constructor`() {
        val constructor = SftpMapperProvider::class.java.getConstructor()

        assertTrue(Modifier.isPublic(constructor.modifiers))
        assertEquals(SftpMapperProvider::class.java, constructor.newInstance()::class.java)
    }

    @Test
    fun `provider is discovered exactly once by its classloader`() {
        val discovered =
            ServiceLoader
                .load(BabelfishMapperProvider::class.java, SftpMapperProvider::class.java.classLoader)
                .toList()
        val implementations = discovered.filter { it::class == SftpMapperProvider::class }

        assertEquals(1, implementations.size)
        assertEquals("sftp", implementations.single().id)
        assertEquals(1, discovered.count { it.id == "sftp" })
    }

    @Test
    fun `provider registers sftp mappings on independent registries`() {
        repeat(2) {
            val registry = BabelfishRegistry().apply { SftpMapperProvider().registerMappings(this) }
            val rawConfig = sftpConfig()
            val mapper = registry.resolveConfig(rawConfig, SOURCE_FILE)
            val config = mapper.map(rawConfig, MappingContext(registry, SOURCE_FILE))

            assertIs<SftpConnectionConfig>(config)
        }
    }

    @Test
    fun `provider registers sftp emitters on independent emitters`() {
        repeat(2) {
            val registry = BabelfishRegistry().apply { SftpMapperProvider().registerMappings(this) }
            val rawConfig = sftpConfig()
            val mapper = registry.resolveConfig(rawConfig, SOURCE_FILE)
            val config = mapper.map(rawConfig, MappingContext(registry, SOURCE_FILE))
            val translationResult =
                TranslationResult(
                    sourceFile = SOURCE_FILE,
                    flows = emptyList(),
                    usedConfigs = mapOf("Sftp_Config" to config),
                )
            val emitter = KotlinCodeEmitter().apply { SftpMapperProvider().registerEmitters(this) }

            assertContains(emitter.emit(translationResult, "com.example"), "sftpConnection")
        }
    }

    private fun sftpConfig(): RawConfig =
        RawConfig(
            id = "internal-uuid",
            namespace = "sftp",
            tag = "config",
            attrs = mapOf("name" to "Sftp_Config", "doc:name" to "SFTP Config"),
            children =
                listOf(
                    RawElement(
                        namespace = "sftp",
                        tag = "connection",
                        attrs =
                            mapOf(
                                "host" to "localhost",
                                "username" to "muse",
                                "password" to "secret",
                                "workingDir" to "/tmp",
                            ),
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

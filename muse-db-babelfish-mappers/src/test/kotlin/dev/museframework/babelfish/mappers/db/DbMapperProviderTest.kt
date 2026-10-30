package dev.museframework.babelfish.mappers.db

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DbMapperProviderTest {
    @Test
    fun `provider has stable id and public constructor`() {
        val constructor = DbMapperProvider::class.java.getConstructor()

        assertTrue(Modifier.isPublic(constructor.modifiers))
        assertEquals("db", constructor.newInstance().id)
    }

    @Test
    fun `provider is discovered exactly once by its classloader`() {
        val providers =
            ServiceLoader.load(BabelfishMapperProvider::class.java, DbMapperProvider::class.java.classLoader)
                .filterIsInstance<DbMapperProvider>()

        assertEquals(1, providers.size)
        assertEquals("db", providers.single().id)
    }

    @Test
    fun `provider registers database mappings on independent registries`() {
        val provider = DbMapperProvider()
        val element =
            RawElement(
                namespace = "db",
                tag = "select",
                attrs = mapOf("config-ref" to "database"),
                children = emptyList(),
                location = SourceLocation(1, 1),
            )
        val first = BabelfishRegistry().also(provider::registerMappings)
        val second = BabelfishRegistry().also(provider::registerMappings)

        assertSame(DbSelectMapper, first.resolveElement(element, Path.of("first.xml")))
        assertSame(DbSelectMapper, second.resolveElement(element, Path.of("second.xml")))
    }
}

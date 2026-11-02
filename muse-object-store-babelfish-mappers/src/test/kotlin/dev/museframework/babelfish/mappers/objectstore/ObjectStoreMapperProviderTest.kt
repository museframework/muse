package dev.museframework.babelfish.mappers.objectstore

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import dev.museframework.objectstore.api.config.ObjectStoreConfig
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ObjectStoreMapperProviderTest {
    @Test
    fun `provider is discovered once and registers independent registries and emitters`() {
        val providers = ServiceLoader.load(BabelfishMapperProvider::class.java).toList().filter { it.id == "object-store" }
        assertEquals(1, providers.size)
        assertIs<ObjectStoreMapperProvider>(providers.single())
        repeat(2) {
            val provider = ObjectStoreMapperProvider::class.java.getConstructor().newInstance()
            val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
            val source = Path.of("object-store.xml")
            val raw = RawConfig("named", "os", "object-store", mapOf("name" to "named"), emptyList(), SourceLocation(1, 1))
            val mapped = registry.resolveConfig(raw, source).map(raw, MappingContext(registry, source))
            assertIs<ObjectStoreConfig>(mapped)
            val result = TranslationResult(source, emptyList(), mapOf("named" to mapped))
            val emitter = KotlinCodeEmitter().apply { provider.registerEmitters(this) }
            assertContains(emitter.emit(result, "example"), "objectStore {")
        }
    }
}

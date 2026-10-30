package dev.museframework.babelfish.api

import dev.museframework.babelfish.exception.BabelfishRegistrationCollisionException
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.babelfish.spi.BabelfishRegistrationOwner
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BabelfishRegistryRegistrationTest {

    @Test
    fun `element mapper collision identifies both owners and retains the first mapper`() {
        val registry = BabelfishRegistry()
        registry.withRegistrationOwner(ALPHA_OWNER) {
            registry.registerElement("test", "shared", FirstElementMapper)
        }

        val exception =
            assertFailsWith<BabelfishRegistrationCollisionException> {
                registry.withRegistrationOwner(BETA_OWNER) {
                    registry.registerElement("test", "shared", SecondElementMapper)
                }
            }

        assertEquals("element mapper", exception.category)
        assertEquals("test:shared", exception.key)
        assertEquals(ALPHA_OWNER, exception.existingOwner)
        assertEquals(BETA_OWNER, exception.incomingOwner)
        assertContains(exception.message!!, FirstElementMapper::class.java.name)
        assertContains(exception.message!!, SecondElementMapper::class.java.name)
        assertSame(
            FirstElementMapper,
            registry.resolveElement(rawElement("test", "shared"), Path.of("flow.xml")),
        )
    }

    @Test
    fun `source and configuration mapper collisions use category-specific keys`() {
        val sourceRegistry = BabelfishRegistry()
        sourceRegistry.withRegistrationOwner(ALPHA_OWNER) {
            sourceRegistry.registerSource("test", "shared", FirstSourceMapper)
        }
        val sourceException =
            assertFailsWith<BabelfishRegistrationCollisionException> {
                sourceRegistry.withRegistrationOwner(BETA_OWNER) {
                    sourceRegistry.registerSource("test", "shared", SecondSourceMapper)
                }
            }

        assertEquals("source mapper", sourceException.category)
        assertSame(
            FirstSourceMapper,
            sourceRegistry.resolveSource(rawElement("test", "shared"), Path.of("flow.xml")),
        )

        val configRegistry = BabelfishRegistry()
        configRegistry.withRegistrationOwner(ALPHA_OWNER) {
            configRegistry.registerConfig("test", "shared", FirstConfigMapper)
        }
        val configException =
            assertFailsWith<BabelfishRegistrationCollisionException> {
                configRegistry.withRegistrationOwner(BETA_OWNER) {
                    configRegistry.registerConfig("test", "shared", SecondConfigMapper)
                }
            }

        assertEquals("configuration mapper", configException.category)
        assertSame(
            FirstConfigMapper,
            configRegistry.resolveConfig(rawConfig("test", "shared"), Path.of("flow.xml")),
        )
    }

    @Test
    fun `same namespace and tag in different mapper categories do not collide`() {
        val registry = BabelfishRegistry()

        registry.withRegistrationOwner(ALPHA_OWNER) {
            registry.registerElement("test", "shared", FirstElementMapper)
            registry.registerSource("test", "shared", FirstSourceMapper)
            registry.registerConfig("test", "shared", FirstConfigMapper)
        }

        assertSame(
            FirstElementMapper,
            registry.resolveElement(rawElement("test", "shared"), Path.of("flow.xml")),
        )
        assertSame(
            FirstSourceMapper,
            registry.resolveSource(rawElement("test", "shared"), Path.of("flow.xml")),
        )
        assertSame(
            FirstConfigMapper,
            registry.resolveConfig(rawConfig("test", "shared"), Path.of("flow.xml")),
        )
    }

    @Test
    fun `registrations outside a provider scope use the direct owner`() {
        val registry = BabelfishRegistry()
        registry.registerElement("test", "shared", FirstElementMapper)

        val exception =
            assertFailsWith<BabelfishRegistrationCollisionException> {
                registry.registerElement("test", "shared", SecondElementMapper)
            }

        assertEquals(BabelfishRegistrationOwner.DIRECT, exception.existingOwner)
        assertEquals(BabelfishRegistrationOwner.DIRECT, exception.incomingOwner)
    }

    private fun rawElement(namespace: String, tag: String) =
        RawElement(
            namespace = namespace,
            tag = tag,
            attrs = emptyMap(),
            children = emptyList(),
            location = SourceLocation(1, 1),
        )

    private fun rawConfig(namespace: String, tag: String) =
        RawConfig(
            id = "config",
            namespace = namespace,
            tag = tag,
            attrs = emptyMap(),
            children = emptyList(),
            location = SourceLocation(1, 1),
        )

    private companion object {
        val ALPHA_OWNER = BabelfishRegistrationOwner("alpha", "example.AlphaProvider")
        val BETA_OWNER = BabelfishRegistrationOwner("beta", "example.BetaProvider")
    }

    private object FirstElementMapper : ElementMapper<PipelineElement> {
        override fun map(element: RawElement, ctx: MappingContext): PipelineElement = error("not used")
    }

    private object SecondElementMapper : ElementMapper<PipelineElement> {
        override fun map(element: RawElement, ctx: MappingContext): PipelineElement = error("not used")
    }

    private object FirstSourceMapper : SourceMapper<Source> {
        override fun map(element: RawElement, ctx: MappingContext): Source = error("not used")
    }

    private object SecondSourceMapper : SourceMapper<Source> {
        override fun map(element: RawElement, ctx: MappingContext): Source = error("not used")
    }

    private object FirstConfigMapper : ConfigMapper {
        override fun map(config: RawConfig, ctx: MappingContext): MuseConfiguration = error("not used")
    }

    private object SecondConfigMapper : ConfigMapper {
        override fun map(config: RawConfig, ctx: MappingContext): MuseConfiguration = error("not used")
    }
}

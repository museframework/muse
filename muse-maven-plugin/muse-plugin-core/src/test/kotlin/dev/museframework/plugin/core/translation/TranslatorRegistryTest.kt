package dev.museframework.plugin.core.translation

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.exception.BabelfishRegistrationCollisionException
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import org.junit.jupiter.api.Test
import java.util.ServiceConfigurationError
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranslatorRegistryTest {

    @Test
    fun `providers are sorted by id then implementation class name`() {
        val order = mutableListOf<String>()
        val providers =
            listOf(
                FakeProvider("zeta", onMapping = { _ -> order.add("zeta") }),
                FakeProvider("alpha", onMapping = { _ -> order.add("alpha") }),
                FakeProvider("mu", onMapping = { _ -> order.add("mu") }),
            )

        val registry = TranslatorRegistry.of(providers)
        registry.createRegistry()

        assertEquals(listOf("alpha", "mu", "zeta"), order)
    }

    @Test
    fun `registry and emitter calls use providers in the same order`() {
        val mappingOrder = mutableListOf<String>()
        val emitterOrder = mutableListOf<String>()
        val providers =
            listOf(
                FakeProvider("beta", { s -> mappingOrder.add(s) }, { id, _ -> emitterOrder.add(id) }),
                FakeProvider("alpha", { s -> mappingOrder.add(s) }, { id, _ -> emitterOrder.add(id) }),
            )

        val registry = TranslatorRegistry.of(providers)
        registry.createRegistry()
        registry.registerEmitters(KotlinCodeEmitter(), nativeBuild = false)

        assertEquals(listOf("alpha", "beta"), mappingOrder)
        assertEquals(listOf("alpha", "beta"), emitterOrder)
    }

    @Test
    fun `nativeBuild is forwarded to emitter registration`() {
        val received = mutableListOf<Boolean>()
        val providers =
            listOf(
                FakeProvider("core") { _, nativeBuild -> received.add(nativeBuild) },
            )

        TranslatorRegistry.of(providers).registerEmitters(KotlinCodeEmitter(), nativeBuild = true)

        assertEquals(listOf(true), received)
    }

    @Test
    fun `blank provider id is rejected`() {
        val exception =
            assertFailsWith<MuseTranslationException> {
                TranslatorRegistry.of(listOf(FakeProvider("")))
            }
        assertContains(exception.message!!, "blank id")
    }

    @Test
    fun `provider id with leading or trailing whitespace is rejected`() {
        listOf(" http", "http ", " http ").forEach { id ->
            val exception =
                assertFailsWith<MuseTranslationException> {
                    TranslatorRegistry.of(listOf(FakeProvider(id)))
                }
            assertContains(exception.message!!, "invalid id '$id'")
            assertContains(exception.message!!, "leading or trailing whitespace")
        }
    }

    @Test
    fun `uppercase provider id is rejected`() {
        val exception =
            assertFailsWith<MuseTranslationException> {
                TranslatorRegistry.of(listOf(FakeProvider("HTTP")))
            }
        assertContains(exception.message!!, "invalid id 'HTTP'")
        assertContains(exception.message!!, "lowercase alphanumeric")
    }

    @Test
    fun `duplicate provider id is rejected with both class names`() {
        val first = FakeProvider("dup")
        val second =
            object : BabelfishMapperProvider {
                override val id = "dup"
                override fun registerMappings(registry: BabelfishRegistry) {}
                override fun registerEmitters(emitter: KotlinCodeEmitter, nativeBuild: Boolean) {}
            }

        val exception =
            assertFailsWith<MuseTranslationException> {
                TranslatorRegistry.of(listOf(first, second))
            }

        assertContains(exception.message!!, "dup")
        assertContains(exception.message!!, first::class.java.name)
        assertContains(exception.message!!, second::class.java.name)
    }

    @Test
    fun `missing core provider is rejected when required`() {
        val exception =
            assertFailsWith<MuseTranslationException> {
                TranslatorRegistry.of(emptyList(), requireCore = true)
            }

        assertContains(exception.message!!, "core mapper module")
    }

    @Test
    fun `discovers core provider on plugin-core test classpath`() {
        val registry = TranslatorRegistry.load()

        val ids = registry.providerIds()
        assertTrue(ids.contains("core"), "Expected core provider, got $ids")
        assertTrue(ids.none { it == "http" }, "Did not expect http provider on plugin-core classpath")
        assertTrue(ids.none { it == "apikit" }, "Did not expect apikit provider on plugin-core classpath")
    }

    @Test
    fun `provider loader service configuration error is propagated`() {
        val brokenClassLoader =
            object : ClassLoader(this::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("dev.museframework.babelfish.mappers.") && name.endsWith("MapperProvider")) {
                        throw ServiceConfigurationError("simulated broken provider: $name")
                    }
                    return super.loadClass(name, resolve)
                }
            }

        val exception =
            assertFailsWith<MuseTranslationException> {
                TranslatorRegistry.load(brokenClassLoader, requireCore = false)
            }

        assertContains(exception.message!!, "Failed to load Babelfish mapper providers")
        assertContains(exception.message!!, "simulated broken provider")
    }

    @Test
    fun `element mapping collision is deterministic and identifies both provider owners`() {
        val firstMessage = elementCollisionMessage(listOf(ZuluElementProvider(), AlphaElementProvider()))
        val reversedMessage = elementCollisionMessage(listOf(AlphaElementProvider(), ZuluElementProvider()))

        assertEquals(firstMessage, reversedMessage)
        assertContains(firstMessage, "element mapper key 'test:shared'")
        assertContains(firstMessage, "owner id='alpha'")
        assertContains(firstMessage, AlphaElementProvider::class.java.name)
        assertContains(firstMessage, "owner id='zulu'")
        assertContains(firstMessage, ZuluElementProvider::class.java.name)
        assertContains(firstMessage, CollidingElementMapper::class.java.name)
    }

    @Test
    fun `source and configuration mapping collisions are rejected`() {
        val sourceException =
            assertFailsWith<MuseTranslationException> {
                TranslatorRegistry
                    .of(
                        listOf(
                            RegisteringProvider("alpha", mappings = {
                                it.registerSource("test", "shared", FirstSourceMapper)
                            }),
                            RegisteringProvider("beta", mappings = {
                                it.registerSource("test", "shared", SecondSourceMapper)
                            }),
                        ),
                    ).createRegistry()
            }
        assertContains(sourceException.message!!, "source mapper key 'test:shared'")

        val configException =
            assertFailsWith<MuseTranslationException> {
                TranslatorRegistry
                    .of(
                        listOf(
                            RegisteringProvider("alpha", mappings = {
                                it.registerConfig("test", "shared", FirstConfigMapper)
                            }),
                            RegisteringProvider("beta", mappings = {
                                it.registerConfig("test", "shared", SecondConfigMapper)
                            }),
                        ),
                    ).createRegistry()
            }
        assertContains(configException.message!!, "configuration mapper key 'test:shared'")
    }

    @Test
    fun `emitter collision is a provider configuration failure`() {
        val exception =
            assertFailsWith<MuseTranslationException> {
                TranslatorRegistry
                    .of(
                        listOf(
                            RegisteringProvider("alpha", emitters = {
                                it.registerElementEmitter(SetPayloadConfig::class, FirstElementEmitter)
                            }),
                            RegisteringProvider("beta", emitters = {
                                it.registerElementEmitter(SetPayloadConfig::class, SecondElementEmitter)
                            }),
                        ),
                    ).registerEmitters(KotlinCodeEmitter())
            }

        assertContains(exception.message!!, "element emitter key '${SetPayloadConfig::class.java.name}'")
        assertContains(exception.message!!, "owner id='alpha'")
        assertContains(exception.message!!, "owner id='beta'")
        assertTrue(exception.cause is BabelfishRegistrationCollisionException)
    }

    private fun elementCollisionMessage(providers: List<BabelfishMapperProvider>): String {
        val exception =
            assertFailsWith<MuseTranslationException> {
                TranslatorRegistry.of(providers).createRegistry()
            }
        assertTrue(exception.cause is BabelfishRegistrationCollisionException)
        return exception.message!!
    }

    private class FakeProvider(
        override val id: String,
        private val onMapping: (String) -> Unit = {},
        private val onEmitter: (String, Boolean) -> Unit = { _, _ -> },
    ) : BabelfishMapperProvider {
        constructor(
            id: String,
            onEmitter: (String, Boolean) -> Unit,
        ) : this(id, {}, onEmitter)

        override fun registerMappings(registry: BabelfishRegistry) {
            onMapping(id)
        }

        override fun registerEmitters(
            emitter: KotlinCodeEmitter,
            nativeBuild: Boolean,
        ) {
            onEmitter(id, nativeBuild)
        }
    }

    private class AlphaElementProvider : BabelfishMapperProvider {
        override val id: String = "alpha"

        override fun registerMappings(registry: BabelfishRegistry) {
            registry.registerElement("test", "shared", CollidingElementMapper)
        }

        override fun registerEmitters(emitter: KotlinCodeEmitter, nativeBuild: Boolean) {}
    }

    private class ZuluElementProvider : BabelfishMapperProvider {
        override val id: String = "zulu"

        override fun registerMappings(registry: BabelfishRegistry) {
            registry.registerElement("test", "shared", CollidingElementMapper)
        }

        override fun registerEmitters(emitter: KotlinCodeEmitter, nativeBuild: Boolean) {}
    }

    private class RegisteringProvider(
        override val id: String,
        private val mappings: (BabelfishRegistry) -> Unit = {},
        private val emitters: (KotlinCodeEmitter) -> Unit = {},
    ) : BabelfishMapperProvider {
        override fun registerMappings(registry: BabelfishRegistry) = mappings(registry)

        override fun registerEmitters(emitter: KotlinCodeEmitter, nativeBuild: Boolean) = emitters(emitter)
    }

    private object CollidingElementMapper : ElementMapper<PipelineElement> {
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

    private object FirstElementEmitter : ElementCodeEmitter<SetPayloadConfig> {
        override fun emit(element: SetPayloadConfig, ctx: dev.museframework.babelfish.emitter.CodegenContext): String =
            "firstEmitter()"
    }

    private object SecondElementEmitter : ElementCodeEmitter<SetPayloadConfig> {
        override fun emit(element: SetPayloadConfig, ctx: dev.museframework.babelfish.emitter.CodegenContext): String =
            "secondEmitter()"
    }
}

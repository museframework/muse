package dev.museframework.plugin.core.translation

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.exception.BabelfishRegistrationCollisionException
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import dev.museframework.babelfish.spi.BabelfishRegistrationOwner
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * Catalog of discovered [BabelfishMapperProvider]s used for one plugin execution.
 *
 * Providers are discovered once per plugin realm via [ServiceLoader]. The same
 * immutable provider list populates XML mapping registries and configures Kotlin
 * code emitters, so parsing and emission cannot accidentally use different mapper
 * sets.
 */
class TranslatorRegistry internal constructor(
    private val providers: List<BabelfishMapperProvider>,
) {
    /**
     * Creates a fresh [BabelfishRegistry] and registers all discovered mappings.
     */
    fun createRegistry(): BabelfishRegistry {
        val registry = BabelfishRegistry()
        try {
            providers.forEach { provider ->
                registry.withRegistrationOwner(provider.registrationOwner()) {
                    provider.registerMappings(registry)
                }
            }
        } catch (e: BabelfishRegistrationCollisionException) {
            throw providerConfigurationFailure(e)
        }
        return registry
    }

    /**
     * Registers all discovered emitters on the given [KotlinCodeEmitter].
     *
     * @param emitter the emitter to configure.
     * @param nativeBuild whether emitted code must target a GraalVM native image.
     */
    fun registerEmitters(
        emitter: KotlinCodeEmitter,
        nativeBuild: Boolean = false,
    ) {
        try {
            providers.forEach { provider ->
                emitter.withRegistrationOwner(provider.registrationOwner()) {
                    provider.registerEmitters(emitter, nativeBuild)
                }
            }
        } catch (e: BabelfishRegistrationCollisionException) {
            throw providerConfigurationFailure(e)
        }
    }

    /**
     * Internal accessor for unit tests.
     */
    internal fun providerIds(): List<String> = providers.map { it.id }

    private fun BabelfishMapperProvider.registrationOwner() =
        BabelfishRegistrationOwner(
            id = id,
            implementationClass = this::class.java.name,
        )

    private fun providerConfigurationFailure(cause: BabelfishRegistrationCollisionException) =
        MuseTranslationException(
            "Failed to configure Babelfish mapper providers: ${cause.message}",
            cause,
        )

    companion object {
        /**
         * Loads the production provider set from the classloader that defines
         * this class. The `core` provider is required.
         */
        fun load(): TranslatorRegistry = load(TranslatorRegistry::class.java.classLoader, requireCore = true)

        /**
         * Internal seam for unit tests. Allows injection of a custom classloader
         * and optional core requirement.
         */
        internal fun load(
            classLoader: ClassLoader,
            requireCore: Boolean,
        ): TranslatorRegistry {
            val instances =
                try {
                    ServiceLoader
                        .load(BabelfishMapperProvider::class.java, classLoader)
                        .toList()
                } catch (e: ServiceConfigurationError) {
                    throw MuseTranslationException(
                        "Failed to load Babelfish mapper providers: ${e.message}. " +
                            "Ensure all mapper artifacts are valid and use the plugin's classloader.",
                        e,
                    )
                }

            val validated = validateProviders(instances, requireCore)
            return TranslatorRegistry(validated)
        }

        /**
         * Internal seam for tests that need a deterministic provider list.
         */
        internal fun of(
            providers: List<BabelfishMapperProvider>,
            requireCore: Boolean = false,
        ): TranslatorRegistry = TranslatorRegistry(validateProviders(providers, requireCore))

        private fun validateProviders(
            providers: List<BabelfishMapperProvider>,
            requireCore: Boolean,
        ): List<BabelfishMapperProvider> {
            if (providers.isEmpty() && requireCore) {
                throw MuseTranslationException(
                    "No Babelfish mapper providers found. " +
                        "The core mapper module must be present on the plugin classpath.",
                )
            }

            providers.forEach { provider ->
                val id = provider.id
                if (id != id.trim()) {
                    throw MuseTranslationException(
                        "Babelfish mapper provider ${provider::class.java.name} has invalid id '$id'. " +
                            "Provider ids must not contain leading or trailing whitespace.",
                    )
                }
                if (id.isBlank()) {
                    throw MuseTranslationException(
                        "Babelfish mapper provider ${provider::class.java.name} has a blank id.",
                    )
                }
                if (!id.matches(Regex("^[a-z][a-z0-9-]*$"))) {
                    throw MuseTranslationException(
                        "Babelfish mapper provider ${provider::class.java.name} has invalid id '$id'. " +
                            "Provider ids must be lowercase alphanumeric with hyphens.",
                    )
                }
            }

            providers
                .groupingBy { it.id }
                .aggregate { _, accumulator: MutableList<String>?, element, _ ->
                    (accumulator ?: mutableListOf()).apply { add(element::class.java.name) }
                }
                .filter { it.value.size > 1 }
                .forEach { (id, classes) ->
                    throw MuseTranslationException(
                        "Duplicate Babelfish mapper provider id '$id' found in providers: ${classes.sorted().joinToString()}",
                    )
                }

            if (requireCore && providers.none { it.id == "core" }) {
                throw MuseTranslationException(
                    "Required Babelfish mapper provider 'core' is missing. " +
                        "Ensure muse-babelfish-core-mappers is on the plugin classpath.",
                )
            }

            return providers.sortedWith(compareBy({ it.id }, { it::class.java.name }))
        }
    }
}

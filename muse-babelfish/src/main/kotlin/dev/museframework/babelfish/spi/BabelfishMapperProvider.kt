package dev.museframework.babelfish.spi

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter

/**
 * Public SPI for Babelfish mapper modules.
 *
 * A provider pairs XML-to-model mapping registration with Kotlin code emission
 * registration for one component family (e.g. core, HTTP, APIKit).
 *
 * Provider classes must:
 * - be public;
 * - expose a public no-argument constructor;
 * - be stateless and immutable;
 * - register the same component set from [registerMappings] and [registerEmitters].
 *
 * Implementations are discovered via `ServiceLoader` from
 * `META-INF/services/dev.museframework.babelfish.spi.BabelfishMapperProvider`.
 */
interface BabelfishMapperProvider {
    /**
     * Stable, non-blank, lowercase identifier owned by the mapper module.
     *
     * Built-in IDs are `core`, `http`, and `apikit`.
     */
    val id: String

    /**
     * Register this provider's XML mappers on the given registry.
     *
     * The registry is fresh for each translation context; this method must be
     * safe to call multiple times on different registry instances.
     */
    fun registerMappings(registry: BabelfishRegistry)

    /**
     * Register this provider's Kotlin code emitters on the given emitter.
     *
     * The emitter is fresh for each translation; this method must be safe to
     * call multiple times on different emitter instances.
     *
     * @param emitter the emitter to configure.
     * @param nativeBuild whether the generated code targets a GraalVM native
     *        image. Providers whose registrars support this flag must forward
     *        it; others may ignore it.
     */
    fun registerEmitters(
        emitter: KotlinCodeEmitter,
        nativeBuild: Boolean = false,
    )
}

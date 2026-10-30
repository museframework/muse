package dev.museframework.plugin.core.runtime

import dev.museframework.plugin.core.runtime.ktor.KtorRuntimeStrategy
import dev.museframework.plugin.core.runtime.quarkus.QuarkusRuntimeStrategy
import dev.museframework.plugin.core.runtime.spring.SpringBootRuntimeStrategy

/**
 * Factory that maps a [MuseRuntime] to its corresponding [RuntimeStrategy].
 *
 * The Mojo obtains the resolved runtime from [RuntimeResolver] and then
 * calls [RuntimeStrategyFactory.get] to obtain the strategy that knows
 * how to validate, generate code, and produce a run command for that runtime.
 */
object RuntimeStrategyFactory {

    private val strategies: Map<MuseRuntime, RuntimeStrategy> = mapOf(
        MuseRuntime.None       to NoRuntimeStrategy(),
        MuseRuntime.SpringBoot to SpringBootRuntimeStrategy(),
        MuseRuntime.Quarkus    to QuarkusRuntimeStrategy(),
        MuseRuntime.Ktor       to KtorRuntimeStrategy()
    )

    /**
     * Returns the [RuntimeStrategy] for the given [runtime].
     *
     * @throws MuseRuntimeException if no strategy is registered for the runtime
     *         (should never happen if [MuseRuntime] sealed class and this map are kept in sync).
     */
    fun get(runtime: MuseRuntime): RuntimeStrategy =
        strategies[runtime]
            ?: throw MuseRuntimeException(
                "No RuntimeStrategy registered for runtime '${runtime.configKey}'. " +
                "This is a bug in muse-maven-plugin — please report it."
            )
}

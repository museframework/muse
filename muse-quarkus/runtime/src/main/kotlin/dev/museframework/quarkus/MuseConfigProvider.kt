package dev.museframework.quarkus

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow

/**
 * Explicit Muse configuration source for Quarkus applications.
 *
 * Prefer this contract over reflection-based [dev.museframework.dsl.api.config.MuseConfigDefinition]
 * when building a native image.
 */
interface MuseConfigProvider {
    fun flows(): List<MuseFlow> = emptyList()

    fun configs(): List<MuseConfiguration> = emptyList()
}

package dev.museframework.dsl.api.model.config

/**
 * A reference to a [MuseConfiguration], either by direct object reference
 * or by name (resolved at init-time from the registered configurations).
 *
 * This mirrors the [dev.museframework.dsl.api.model.flow.FlowRef] pattern for flow references.
 *
 * - [ByReference]: Type-safe, compile-time reference. Preferred approach.
 * - [ByName]: String-based lookup resolved at bootstrap. Useful for generated code
 *   (e.g. babelfish) or when the config is defined in a separate module/context.
 */
sealed class ConfigRef {
    data class ByReference(
        val config: MuseConfiguration,
    ) : ConfigRef()

    data class ByName(
        val name: String,
    ) : ConfigRef()
}
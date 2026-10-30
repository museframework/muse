package dev.museframework.apikit.api.config

/**
 * A reference to an [ApikitConfig], either by direct object reference
 * or by name (resolved at init-time from the registered configurations).
 *
 * This mirrors the [dev.museframework.http.api.config.ConnectionConfigRef] pattern.
 *
 * - [ByReference]: Type-safe, compile-time reference. Preferred approach.
 * - [ByName]: String-based lookup resolved at bootstrap. Useful for generated code
 *   (e.g. babelfish) or when the config is defined in a separate module/context.
 */
sealed class ApikitRef {
    data class ByReference(
        val config: ApikitConfig,
    ) : ApikitRef()

    data class ByName(
        val name: String,
    ) : ApikitRef()
}

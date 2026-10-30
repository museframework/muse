package dev.museframework.plugin.core.codegen

/**
 * A generated project file.
 *
 * [relativePath] is relative to the generated source root. Resource files use
 * a `resources/` prefix so both build-time and in-place writers can preserve
 * the same layout.
 */
data class GeneratedFile(
    val relativePath: String,
    val content: String,
    val kind: Kind = Kind.KOTLIN_SOURCE,
) {
    enum class Kind {
        KOTLIN_SOURCE,
        RESOURCE,
    }
}

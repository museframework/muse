package dev.museframework.plugin.core.runtime

/**
 * Resolves the target runtime by combining explicit plugin configuration
 * with automatic detection from project dependencies.
 *
 * Resolution order:
 *   1. Explicit `<runtime>` in plugin config — always wins, skips detection entirely.
 *   2. Auto-detection from compile/runtime dependencies — fallback.
 *   3. Ambiguous detection — fail with a message that tells the user to set `<runtime>`.
 *   4. Nothing found — fail with a message that tells the user to add a dependency.
 */
object RuntimeResolver {

    fun resolve(explicitConfig: String?, artifacts: List<MavenArtifact>): MuseRuntime {
        if (!explicitConfig.isNullOrBlank()) {
            return MuseRuntime.fromConfigKey(explicitConfig)
        }

        return when (val result = RuntimeDetector.detect(artifacts)) {
            is RuntimeDetector.DetectionResult.Found ->
                result.runtime

            is RuntimeDetector.DetectionResult.Ambiguous ->
                throw MuseRuntimeException(
                    "Multiple Muse-compatible runtimes detected: " +
                    "${result.found.map { it.configKey }}. " +
                    "Specify the runtime explicitly in your pom.xml:\n" +
                    "  <configuration>\n" +
                    "    <runtime>spring-boot</runtime>\n" +
                    "  </configuration>"
                )

            RuntimeDetector.DetectionResult.NotFound -> {
                if (explicitConfig.isNullOrBlank()) {
                    MuseRuntime.None
                } else {
                    throw MuseRuntimeException(
                        "No Muse-compatible runtime found in compile/runtime dependencies. " +
                        "Add a Muse runtime dependency (e.g. muse-spring-boot) or declare " +
                        "<runtime> explicitly in the plugin configuration."
                    )
                }
            }
        }
    }
}

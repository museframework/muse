package dev.museframework.plugin.core.codegen

import dev.museframework.plugin.core.runtime.MavenArtifact

/**
 * Input to the code generation layer. All runtime strategies receive this.
 */
data class MuseAppConfig(
    /** Base Kotlin package for generated code. */
    val packageName: String,

    /** PascalCase application name derived from the Maven artifactId. */
    val applicationName: String,

    /**
     * Simple class names of all @MuseConfig classes produced by translation.
     * Used by the entry point generator to register them at startup.
     * Example: ["HelloFlowConfig", "OrderFlowConfig"]
     */
    val flowConfigClassNames: List<String>,

    /**
     * All compile/runtime artifacts of the target project.
     * Passed to strategies to allow fine-grained validation and detection
     * (e.g. KtorStrategy detecting the server engine variant from artifacts).
     */
    val artifacts: List<MavenArtifact>,

    /** Mule namespaces discovered while translating a migration source tree. */
    val namespaces: Set<String> = emptySet(),
)

package dev.museframework.plugin.core.runtime

/**
 * A lightweight representation of a Maven dependency, fully decoupled from the Maven API.
 * This allows [RuntimeDetector] and [RuntimeResolver] to be tested without a Maven container.
 */
data class MavenArtifact(
    val groupId: String,
    val artifactId: String,
    val scope: String       // "compile", "runtime", "test", "provided", etc.
) {
    /**
     * Returns true for scopes that indicate runtime presence.
     * Excludes "test" and "provided" to prevent false positives
     * (e.g. spring-boot-test triggering Spring Boot detection).
     */
    fun isRelevant(): Boolean = scope.lowercase() in setOf("compile", "runtime")
}

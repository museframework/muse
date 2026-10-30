package dev.museframework.plugin.core.codegen

/**
 * A validation error found during pre-flight checks.
 *
 * @property message  Human-readable description including a suggested fix.
 * @property fatal    If true → throw MojoExecutionException.
 *                    If false → log.warn only.
 */
data class ValidationError(
    val message: String,
    val fatal: Boolean
)

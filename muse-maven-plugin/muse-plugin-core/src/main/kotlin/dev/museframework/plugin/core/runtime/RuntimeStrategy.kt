package dev.museframework.plugin.core.runtime

import dev.museframework.babelfish.emitter.ConfigurationClassSpec
import dev.museframework.plugin.core.codegen.GeneratedFile
import dev.museframework.plugin.core.codegen.MigrationProjectConfig
import dev.museframework.plugin.core.codegen.MuseAppConfig
import dev.museframework.plugin.core.codegen.ValidationError

/**
 * Owns every runtime-specific decision used by the Maven goals.
 *
 * Mojos resolve one strategy and never branch on Spring Boot, Quarkus, or any
 * future runtime themselves.
 */
interface RuntimeStrategy {
    val runtime: MuseRuntime

    /** Validates an existing target project before generation or execution. */
    fun validate(config: MuseAppConfig): List<ValidationError>

    /**
     * Validates an in-place migration. The source project is not expected to
     * contain the target runtime dependencies yet.
     */
    fun validateMigration(config: MuseAppConfig): List<ValidationError> = validate(config)

    /** Describes how generated configuration classes are discovered at runtime. */
    fun configurationClassSpec(): ConfigurationClassSpec

    /** Runtime-owned Kotlin sources and resources generated for an application. */
    fun generateRuntimeFiles(config: MuseAppConfig): List<GeneratedFile>

    /** Generates the target runtime's complete Maven project descriptor. */
    fun generatePom(config: MigrationProjectConfig): String

    fun runCommand(config: MuseAppConfig): RunCommand

    fun buildCommand(config: MuseAppConfig): RunCommand

    fun dockerCommand(config: MuseAppConfig): RunCommand
}

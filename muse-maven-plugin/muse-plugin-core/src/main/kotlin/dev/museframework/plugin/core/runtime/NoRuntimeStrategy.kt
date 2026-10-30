package dev.museframework.plugin.core.runtime

import dev.museframework.babelfish.emitter.ConfigurationClassSpec
import dev.museframework.plugin.core.codegen.GeneratedFile
import dev.museframework.plugin.core.codegen.MigrationProjectConfig
import dev.museframework.plugin.core.codegen.MuseAppConfig
import dev.museframework.plugin.core.codegen.ValidationError

/** Generates portable DSL classes when no application runtime is present. */
class NoRuntimeStrategy : RuntimeStrategy {
    override val runtime = MuseRuntime.None

    override fun validate(config: MuseAppConfig): List<ValidationError> =
        listOf(
            ValidationError(
                message =
                    "No Muse-compatible runtime detected. DSL configuration classes will be " +
                        "generated without an application bootstrap. Add muse-spring-boot or " +
                        "muse-quarkus to generate runtime integration files.",
                fatal = false,
            ),
        )

    override fun validateMigration(config: MuseAppConfig): List<ValidationError> =
        listOf(
            ValidationError(
                message = "Migration requires a target runtime: spring-boot or quarkus.",
                fatal = true,
            ),
        )

    override fun configurationClassSpec(): ConfigurationClassSpec = ConfigurationClassSpec.Plain

    override fun generateRuntimeFiles(config: MuseAppConfig): List<GeneratedFile> = emptyList()

    override fun generatePom(config: MigrationProjectConfig): String =
        throw MuseRuntimeException("Cannot generate a migrated project without a target runtime.")

    override fun runCommand(config: MuseAppConfig): RunCommand =
        throw MuseRuntimeException(
            "Cannot run without a Muse-compatible runtime. " +
                "Add a runtime dependency or set <runtime> in plugin configuration.",
        )

    override fun buildCommand(config: MuseAppConfig): RunCommand =
        throw MuseRuntimeException(
            "Cannot build without a Muse-compatible runtime. " +
                "Add a runtime dependency or set <runtime> in plugin configuration.",
        )

    override fun dockerCommand(config: MuseAppConfig): RunCommand =
        throw MuseRuntimeException(
            "Cannot build an image without a Muse-compatible runtime. " +
                "Add a runtime dependency or set <runtime> in plugin configuration.",
        )
}

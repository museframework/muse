package dev.museframework.plugin.core.runtime.ktor

import dev.museframework.babelfish.emitter.ConfigurationClassSpec
import dev.museframework.plugin.core.codegen.GeneratedFile
import dev.museframework.plugin.core.codegen.MigrationProjectConfig
import dev.museframework.plugin.core.codegen.MuseAppConfig
import dev.museframework.plugin.core.codegen.ValidationError
import dev.museframework.plugin.core.runtime.MuseRuntime
import dev.museframework.plugin.core.runtime.MuseRuntimeException
import dev.museframework.plugin.core.runtime.RunCommand
import dev.museframework.plugin.core.runtime.RuntimeStrategy

/** Explicit unsupported boundary until a Muse Ktor runtime integration exists. */
class KtorRuntimeStrategy : RuntimeStrategy {
    override val runtime = MuseRuntime.Ktor

    override fun validate(config: MuseAppConfig): List<ValidationError> =
        listOf(
            ValidationError(
                message = "Ktor runtime is not supported by muse-maven-plugin.",
                fatal = true,
            ),
        )

    override fun configurationClassSpec(): ConfigurationClassSpec = ConfigurationClassSpec.Plain

    override fun generateRuntimeFiles(config: MuseAppConfig): List<GeneratedFile> = unsupported()

    override fun generatePom(config: MigrationProjectConfig): String = unsupported()

    override fun runCommand(config: MuseAppConfig): RunCommand = unsupported()

    override fun buildCommand(config: MuseAppConfig): RunCommand = unsupported()

    override fun dockerCommand(config: MuseAppConfig): RunCommand = unsupported()

    private fun <T> unsupported(): T =
        throw MuseRuntimeException("Ktor runtime is not supported by muse-maven-plugin.")
}

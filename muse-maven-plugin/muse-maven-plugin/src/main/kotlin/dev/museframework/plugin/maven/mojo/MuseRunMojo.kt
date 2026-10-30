package dev.museframework.plugin.maven.mojo

import dev.museframework.plugin.core.codegen.MuseAppConfig
import dev.museframework.plugin.core.runtime.*
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.BuildPluginManager
import org.apache.maven.plugin.MojoExecution
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.twdata.maven.mojoexecutor.MojoExecutor.*
import java.io.File

@Mojo(
    name = "run",
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = false,
)
class MuseRunMojo : AbstractMojo() {
    @Parameter(property = "muse.runtime", required = false)
    var runtime: String? = null

    @Parameter(
        defaultValue = "com.example.muse",
        property = "muse.packageName",
    )
    lateinit var packageName: String

    @Parameter(
        defaultValue = "\${project.basedir}/src/main/mule",
        property = "muse.sourceDirectory",
        required = true,
    )
    lateinit var sourceDirectory: File

    @Parameter(
        defaultValue = "\${project.build.directory}/generated-sources/muse",
        property = "muse.outputDirectory",
        required = true,
    )
    lateinit var outputDirectory: File

    /**
     * When false (default), muse:run re-runs muse:generate before starting.
     * Set true in rapid iteration to skip regeneration.
     */
    @Parameter(property = "muse.skipGenerate", defaultValue = "false")
    var skipGenerate: Boolean = false

    @Parameter(property = "muse.nativeBuild", defaultValue = "false")
    var nativeBuild: Boolean = false

    @Parameter(property = "muse.lenient", defaultValue = "false")
    var lenient: Boolean = false

    @Parameter(property = "muse.skip", defaultValue = "false")
    var skip: Boolean = false

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var mavenProject: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    lateinit var mavenSession: MavenSession

    @Component
    lateinit var pluginManager: BuildPluginManager

    @Parameter(defaultValue = "\${mojoExecution}", readonly = true, required = true)
    lateinit var mojoExecution: MojoExecution

    override fun execute() {
        if (skip) {
            log.info("Muse run skipped (muse.skip=true)")
            return
        }

        if (!skipGenerate) {
            generateSources()
        }

        val artifacts =
            mavenProject.artifacts.map { a ->
                MavenArtifact(a.groupId, a.artifactId, a.scope ?: "compile")
            }

        val resolvedRuntime =
            try {
                RuntimeResolver.resolve(runtime, artifacts)
            } catch (e: MuseRuntimeException) {
                throw MojoExecutionException(e.message, e)
            }
        log.info("Muse run — runtime: ${resolvedRuntime.configKey}")

        val strategy = RuntimeStrategyFactory.get(resolvedRuntime)

        // Discover generated @MuseConfig classes from target/generated-sources
        val generatedSourcesDir = File(mavenProject.build.directory, "generated-sources/muse")
        val configClassNames =
            if (generatedSourcesDir.exists()) {
                generatedSourcesDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && !it.name.contains("Application") }
                    .map { it.nameWithoutExtension }
                    .toList()
            } else {
                emptyList()
            }

        val appConfig =
            MuseAppConfig(
                packageName = packageName,
                applicationName = toApplicationName(mavenProject.artifactId),
                flowConfigClassNames = configClassNames,
                artifacts = artifacts,
            )

        strategy.validate(appConfig).forEach { error ->
            if (error.fatal) {
                throw MojoExecutionException(error.message)
            } else {
                log.warn(error.message)
            }
        }

        when (val cmd = strategy.runCommand(appConfig)) {
            is RunCommand.DelegateMavenPlugin -> delegateToPlugin(cmd)
            is RunCommand.ExecMainClass -> execMainClass(cmd)
        }
    }

    private fun generateSources() {
        val generateConfiguration =
            mutableListOf(
                element("sourceDirectory", sourceDirectory.absolutePath),
                element("outputDirectory", outputDirectory.absolutePath),
                element("packageName", packageName),
                element("nativeBuild", nativeBuild.toString()),
                element("lenient", lenient.toString()),
            )
        runtime?.takeIf { it.isNotBlank() }?.let {
            generateConfiguration += element("runtime", it)
        }

        executeMojo(
            plugin(
                mojoExecution.plugin.groupId,
                mojoExecution.plugin.artifactId,
                mojoExecution.plugin.version,
            ),
            goal("generate"),
            configuration(*generateConfiguration.toTypedArray()),
            executionEnvironment(mavenProject, mavenSession, pluginManager),
        )
    }

    private fun delegateToPlugin(cmd: RunCommand.DelegateMavenPlugin) {
        val pluginInProject =
            mavenProject.buildPlugins.firstOrNull {
                it.groupId == cmd.groupId && it.artifactId == cmd.artifactId
            } ?: throw MojoExecutionException(
                "${cmd.artifactId} is not declared in <build><plugins>. " +
                    "Add it to your pom.xml to use muse:run with the ${resolveRuntime()} runtime.",
            )

        executeMojo(
            plugin(cmd.groupId, cmd.artifactId, pluginInProject.version),
            goal(cmd.goal),
            configuration(*cmd.configuration.map { (k, v) -> element(k, v) }.toTypedArray()),
            executionEnvironment(mavenProject, mavenSession, pluginManager),
        )
    }

    private fun execMainClass(cmd: RunCommand.ExecMainClass) {
        executeMojo(
            plugin("org.codehaus.mojo", "exec-maven-plugin", "3.1.0"),
            goal("java"),
            configuration(
                element("mainClass", cmd.mainClass),
                element(
                    "arguments",
                    *cmd.jvmArgs.map { element("argument", it) }.toTypedArray(),
                ),
            ),
            executionEnvironment(mavenProject, mavenSession, pluginManager),
        )
    }

    private fun resolveRuntime() = runtime ?: "auto-detected"

    private fun toApplicationName(artifactId: String): String =
        artifactId
            .split("-", "_")
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}

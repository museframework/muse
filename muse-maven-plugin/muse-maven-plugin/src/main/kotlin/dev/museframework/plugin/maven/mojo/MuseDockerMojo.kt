package dev.museframework.plugin.maven.mojo

import dev.museframework.plugin.core.codegen.MuseAppConfig
import dev.museframework.plugin.core.runtime.*
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.BuildPluginManager
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.twdata.maven.mojoexecutor.MojoExecutor.*

@Mojo(
    name = "build-image",
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = false
)
class MuseDockerMojo : AbstractMojo() {

    @Parameter(property = "muse.runtime", required = false)
    var runtime: String? = null

    @Parameter(
        defaultValue = "com.example.muse",
        property = "muse.packageName"
    )
    lateinit var packageName: String

    @Parameter(property = "muse.skip", defaultValue = "false")
    var skip: Boolean = false

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var mavenProject: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    lateinit var mavenSession: MavenSession

    @Component
    lateinit var pluginManager: BuildPluginManager

    override fun execute() {
        if (skip) {
            log.info("Muse docker skipped (muse.skip=true)")
            return
        }

        val artifacts = mavenProject.artifacts.map { a ->
            MavenArtifact(a.groupId, a.artifactId, a.scope ?: "compile")
        }

        val resolvedRuntime = try {
            RuntimeResolver.resolve(runtime, artifacts)
        } catch (e: MuseRuntimeException) {
            throw MojoExecutionException(e.message, e)
        }
        log.info("Muse docker — runtime: ${resolvedRuntime.configKey}")

        val strategy = RuntimeStrategyFactory.get(resolvedRuntime)

        val appConfig = MuseAppConfig(
            packageName = packageName,
            applicationName = mavenProject.artifactId,
            flowConfigClassNames = emptyList(),
            artifacts = artifacts
        )

        strategy.validate(appConfig).forEach { error ->
            if (error.fatal) throw MojoExecutionException(error.message)
            else log.warn(error.message)
        }

        when (val cmd = strategy.dockerCommand(appConfig)) {
            is RunCommand.DelegateMavenPlugin -> delegateToPlugin(cmd)
            is RunCommand.ExecMainClass -> execMainClass(cmd)
        }
    }

    private fun delegateToPlugin(cmd: RunCommand.DelegateMavenPlugin) {
        val pluginInProject = mavenProject.buildPlugins.firstOrNull {
            it.groupId == cmd.groupId && it.artifactId == cmd.artifactId
        } ?: throw MojoExecutionException(
            "${cmd.artifactId} is not declared in <build><plugins>. " +
            "Add it to your pom.xml to use muse:docker with the ${resolveRuntime()} runtime."
        )

        executeMojo(
            plugin(cmd.groupId, cmd.artifactId, pluginInProject.version),
            goal(cmd.goal),
            configuration(*cmd.configuration.map { (k, v) -> element(k, v) }.toTypedArray()),
            executionEnvironment(mavenProject, mavenSession, pluginManager)
        )
    }

    private fun execMainClass(cmd: RunCommand.ExecMainClass) {
        executeMojo(
            plugin("org.codehaus.mojo", "exec-maven-plugin", "3.1.0"),
            goal("java"),
            configuration(
                element("mainClass", cmd.mainClass),
                element("arguments",
                    *cmd.jvmArgs.map { element("argument", it) }.toTypedArray()
                )
            ),
            executionEnvironment(mavenProject, mavenSession, pluginManager)
        )
    }

    private fun resolveRuntime() = runtime ?: "auto-detected"
}

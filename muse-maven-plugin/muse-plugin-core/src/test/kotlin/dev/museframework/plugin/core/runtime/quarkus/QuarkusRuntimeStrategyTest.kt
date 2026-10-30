package dev.museframework.plugin.core.runtime.quarkus

import dev.museframework.plugin.core.codegen.GeneratedFile
import dev.museframework.plugin.core.codegen.MigrationProjectConfig
import dev.museframework.plugin.core.codegen.MuseAppConfig
import dev.museframework.plugin.core.runtime.MavenArtifact
import dev.museframework.plugin.core.runtime.RunCommand
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuarkusRuntimeStrategyTest {
    private val strategy = QuarkusRuntimeStrategy()

    private fun config(
        artifacts: List<MavenArtifact> =
            listOf(MavenArtifact("dev.museframework.connectors", "muse-quarkus", "compile")),
        flowClassNames: List<String> = listOf("MyFlowConfig"),
        namespaces: Set<String> = emptySet(),
    ) = MuseAppConfig(
        packageName = "com.example.muse",
        applicationName = "MyApp",
        flowConfigClassNames = flowClassNames,
        artifacts = artifacts,
        namespaces = namespaces,
    )

    @Test
    fun `validates Muse Quarkus extension dependency`() {
        assertFalse(strategy.validate(config()).any { it.fatal })
        val genericQuarkusOnly = listOf(MavenArtifact("io.quarkus", "quarkus-arc", "compile"))
        assertTrue(strategy.validate(config(artifacts = genericQuarkusOnly)).any { it.fatal })
    }

    @Test
    fun `migration does not require existing extension dependency`() {
        assertFalse(strategy.validateMigration(config(artifacts = emptyList())).any { it.fatal })
    }

    @Test
    fun `migration rejects APIKit until a Quarkus adapter exists`() {
        val errors = strategy.validateMigration(config(namespaces = setOf("apikit")))
        assertTrue(errors.any { it.fatal && "APIKit" in it.message })
    }

    @Test
    fun `uses CDI discovery for JVM and provider for native generation`() {
        val spec = strategy.configurationClassSpec()
        assertContains(spec.definition.annotationTypes, "jakarta.enterprise.context.ApplicationScoped")
        assertContains(spec.definition.annotationTypes, "dev.museframework.dsl.annotation.MuseConfig")
        assertEquals("dev.museframework.quarkus.MuseConfigProvider", spec.native?.providerInterface)
    }

    @Test
    fun `generates Quarkus application properties without synthetic entry point`() {
        val files = strategy.generateRuntimeFiles(config())

        assertEquals(1, files.size)
        assertEquals(GeneratedFile.Kind.RESOURCE, files.single().kind)
        assertEquals("resources/application.properties", files.single().relativePath)
        assertContains(files.single().content, "quarkus.application.name=MyApp")
    }

    @Test
    fun `generates Quarkus migration POM with HTTP and native support`() {
        val pom =
            strategy.generatePom(
                MigrationProjectConfig(
                    groupId = "com.example",
                    artifactId = "orders",
                    version = "1.0.0",
                    namespaces = setOf("http"),
                    packageName = "com.example.orders",
                    nativeBuild = true,
                ),
            )

        assertContains(pom, "<artifactId>quarkus-maven-plugin</artifactId>")
        assertContains(pom, "<artifactId>muse-quarkus</artifactId>")
        assertContains(pom, "<artifactId>muse-http-connector-quarkus</artifactId>")
        assertContains(pom, "<artifactId>quarkus-container-image-jib</artifactId>")
        assertContains(pom, "<quarkus.native.enabled>true</quarkus.native.enabled>")
    }

    @Test
    fun `delegates run build and image goals to Quarkus plugin`() {
        val run = strategy.runCommand(config()) as RunCommand.DelegateMavenPlugin
        val build = strategy.buildCommand(config()) as RunCommand.DelegateMavenPlugin
        val image = strategy.dockerCommand(config()) as RunCommand.DelegateMavenPlugin

        assertEquals("io.quarkus", run.groupId)
        assertEquals("quarkus-maven-plugin", run.artifactId)
        assertEquals("dev", run.goal)
        assertEquals("build", build.goal)
        assertEquals("image-build", image.goal)
    }
}

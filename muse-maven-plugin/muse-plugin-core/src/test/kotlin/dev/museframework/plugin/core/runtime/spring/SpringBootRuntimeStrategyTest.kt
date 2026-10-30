package dev.museframework.plugin.core.runtime.spring

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

class SpringBootRuntimeStrategyTest {
    private val strategy = SpringBootRuntimeStrategy()

    private fun config(
        artifacts: List<MavenArtifact> =
            listOf(MavenArtifact("dev.museframework.connectors", "muse-spring-boot", "compile")),
        flowClassNames: List<String> = listOf("MyFlowConfig"),
    ) = MuseAppConfig(
        packageName = "com.example.muse",
        applicationName = "MyApp",
        flowConfigClassNames = flowClassNames,
        artifacts = artifacts,
    )

    @Test
    fun `validates Muse Spring integration dependency`() {
        assertFalse(strategy.validate(config()).any { it.fatal })
        val genericSpringOnly =
            listOf(MavenArtifact("org.springframework.boot", "spring-boot-autoconfigure", "compile"))
        assertTrue(strategy.validate(config(artifacts = genericSpringOnly)).any { it.fatal })
    }

    @Test
    fun `migration does not require target runtime dependency`() {
        assertFalse(strategy.validateMigration(config(artifacts = emptyList())).any { it.fatal })
    }

    @Test
    fun `warns when no configuration classes were generated`() {
        val errors = strategy.validate(config(flowClassNames = emptyList()))
        assertTrue(errors.any { !it.fatal })
        assertFalse(errors.any { it.fatal })
    }

    @Test
    fun `uses Spring stereotypes for JVM and provider for native generation`() {
        val spec = strategy.configurationClassSpec()
        assertEquals(
            listOf("dev.museframework.springboot.MuseConfigComponent"),
            spec.definition.annotationTypes,
        )
        assertEquals(
            "dev.museframework.springboot.MuseConfigProvider",
            spec.native?.providerInterface,
        )
        assertContains(spec.native!!.annotationTypes, "org.springframework.stereotype.Component")
    }

    @Test
    fun `generates Spring entry point and application resource`() {
        val files = strategy.generateRuntimeFiles(config())
        val entryPoint = files.single { it.kind == GeneratedFile.Kind.KOTLIN_SOURCE }
        val properties = files.single { it.kind == GeneratedFile.Kind.RESOURCE }

        assertTrue(entryPoint.relativePath.startsWith("com/example/muse/"))
        assertContains(entryPoint.content, "runMuseApplication<")
        assertContains(entryPoint.content, "(args)")
        assertEquals("resources/application-muse.properties", properties.relativePath)
        assertContains(properties.content, "muse.application.name=MyApp")
    }

    @Test
    fun `generates Spring migration POM with namespace adapters`() {
        val pom =
            strategy.generatePom(
                MigrationProjectConfig(
                    groupId = "com.example",
                    artifactId = "orders",
                    version = "1.0.0",
                    namespaces = setOf("http", "apikit"),
                    packageName = "com.example.orders",
                ),
            )

        assertContains(pom, "<artifactId>spring-boot-starter-parent</artifactId>")
        assertContains(pom, "<artifactId>muse-spring-boot</artifactId>")
        assertContains(pom, "<artifactId>muse-http-connector-spring</artifactId>")
        assertContains(pom, "<artifactId>muse-apikit-connector-spring</artifactId>")
    }

    @Test
    fun `delegates run build and image goals to Spring Boot plugin`() {
        val run = strategy.runCommand(config()) as RunCommand.DelegateMavenPlugin
        val build = strategy.buildCommand(config()) as RunCommand.DelegateMavenPlugin
        val image = strategy.dockerCommand(config()) as RunCommand.DelegateMavenPlugin

        assertEquals("spring-boot-maven-plugin", run.artifactId)
        assertEquals("run", run.goal)
        assertEquals("repackage", build.goal)
        assertEquals("build-image", image.goal)
    }
}

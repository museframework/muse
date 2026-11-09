package dev.museframework.plugin.maven.mojo

import org.apache.maven.model.Dependency
import org.apache.maven.model.Plugin
import org.apache.maven.plugin.MojoExecution
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MuseRunMojoTest {

    @Test
    fun `delegated generation keeps the configured plugin dependencies`() {
        val mojo = MuseRunMojo()
        mojo.mojoExecution =
            MojoExecution(
                Plugin().apply {
                    groupId = "dev.museframework.maven"
                    artifactId = "muse-maven-plugin"
                    version = "0.1.0"
                    dependencies =
                        listOf(
                            Dependency().apply {
                                groupId = "dev.museframework"
                                artifactId = "muse-http-babelfish-mappers"
                                version = "0.1.0"
                            },
                            Dependency().apply {
                                groupId = "dev.museframework"
                                artifactId = "muse-apikit-babelfish-mappers"
                                version = "0.1.0"
                            },
                        )
                },
                "generate",
                "muse-generate",
            )

        val delegated = mojo.generationPlugin()

        assertEquals("dev.museframework.maven", delegated.groupId)
        assertEquals("muse-maven-plugin", delegated.artifactId)
        assertEquals("0.1.0", delegated.version)
        assertEquals(
            listOf("muse-http-babelfish-mappers", "muse-apikit-babelfish-mappers"),
            delegated.dependencies.map(Dependency::getArtifactId),
        )
    }
}

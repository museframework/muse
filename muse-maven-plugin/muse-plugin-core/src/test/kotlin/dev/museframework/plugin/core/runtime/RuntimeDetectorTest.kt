package dev.museframework.plugin.core.runtime

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeDetectorTest {

    private fun artifact(artifactId: String, scope: String = "compile") =
        MavenArtifact("any.group", artifactId, scope)

    @Test
    fun `detects Spring Boot from muse-spring-boot`() {
        val result = RuntimeDetector.detect(listOf(artifact("muse-spring-boot")))
        assertTrue(result is RuntimeDetector.DetectionResult.Found)
        assertEquals(MuseRuntime.SpringBoot, (result as RuntimeDetector.DetectionResult.Found).runtime)
    }

    @Test
    fun `detects Spring Boot from spring-boot-autoconfigure`() {
        val result = RuntimeDetector.detect(listOf(artifact("spring-boot-autoconfigure")))
        assertTrue(result is RuntimeDetector.DetectionResult.Found)
        assertEquals(MuseRuntime.SpringBoot, (result as RuntimeDetector.DetectionResult.Found).runtime)
    }

    @Test
    fun `ignores test-scoped spring-boot artifact`() {
        val result = RuntimeDetector.detect(listOf(artifact("spring-boot-autoconfigure", "test")))
        assertTrue(result is RuntimeDetector.DetectionResult.NotFound)
    }

    @Test
    fun `ignores provided-scoped artifact`() {
        val result = RuntimeDetector.detect(listOf(artifact("quarkus-core", "provided")))
        assertTrue(result is RuntimeDetector.DetectionResult.NotFound)
    }

    @Test
    fun `returns Ambiguous when Spring Boot and Quarkus both present`() {
        val result = RuntimeDetector.detect(listOf(
            artifact("muse-spring-boot"),
            artifact("quarkus-core")
        ))
        assertTrue(result is RuntimeDetector.DetectionResult.Ambiguous)
    }

    @Test
    fun `returns NotFound for empty list`() {
        val result = RuntimeDetector.detect(emptyList())
        assertTrue(result is RuntimeDetector.DetectionResult.NotFound)
    }
}

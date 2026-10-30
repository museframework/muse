package dev.museframework.apikit.quarkus.deployment

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApikitExtensionBuildStepTest {
    @Test
    fun `native resource patterns include RAML and YAML specifications`() {
        val patterns = ApikitConnectorQuarkusProcessor().nativeResources().includePatterns

        assertTrue(patterns.matches("api/spec.raml"))
        assertTrue(patterns.matches("api/spec.yaml"))
        assertTrue(patterns.matches("api/spec.yml"))
        assertFalse(patterns.matches("api/spec.json"))
    }

    private fun List<String>.matches(path: String): Boolean =
        any { pattern -> Regex(pattern).matches(path) }
}

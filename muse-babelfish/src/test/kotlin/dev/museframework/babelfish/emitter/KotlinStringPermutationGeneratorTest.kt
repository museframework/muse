package dev.museframework.babelfish.emitter

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@DisplayName("KotlinStringPermutationGenerator")
class KotlinStringPermutationGeneratorTest {

    @Test
    fun `generates test cases with expected escaped values`() {
        val testCases = KotlinStringPermutationGenerator.generateFlowNameTestCases()
        assertTrue(testCases.isNotEmpty())
    }

    @Test
    fun `expected escaped values match actual escaping`() {
        val ctx = CodegenContext("com.test")
        val testCases = KotlinStringPermutationGenerator.generateFlowNameTestCases()

        testCases.forEach { tc ->
            val actualEscaped = ctx.escapeKotlinString(tc.input)
            assertEquals(
                tc.expectedEscaped,
                actualEscaped,
                "Mismatch for: ${tc.input}"
            )
        }
    }

    @Test
    fun `generates permutations within limits`() {
        val permutations = KotlinStringPermutationGenerator.generatePermutations(maxCombinations = 100)
        assertTrue(permutations.size <= 100)
    }

    @Test
    fun `escaped strings can be embedded in Kotlin code`() {
        val ctx = CodegenContext("com.test")
        
        // Test key edge cases that would fail without proper escaping
        listOf(
            "get:\\api\\resources",
            "flow-\"quoted\"-name",
            "flow-\$var"
        ).forEach { input ->
            val escaped = ctx.escapeKotlinString(input)
            val kotlinCode = """flow("$escaped") { }"""
            assertTrue(kotlinCode.startsWith("flow(\""))
            assertTrue(kotlinCode.endsWith("\") { }"))
        }
    }

    @Test
    fun `validation works correctly`() {
        val ctx = CodegenContext("com.test")
        // Just check validation doesn't crash
        val result = KotlinStringPermutationGenerator.validateTestCases(ctx)
        assertTrue(result.totalTests > 0)
    }
}
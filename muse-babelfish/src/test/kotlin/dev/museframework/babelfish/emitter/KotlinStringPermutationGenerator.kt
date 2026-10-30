package dev.museframework.babelfish.emitter

/**
 * Generates permutations of strings with special characters for testing Kotlin string escaping.
 *
 * This utility helps identify edge cases where special characters in flow names, config names,
 * or other identifiers could break generated Kotlin code if not properly escaped.
 */
object KotlinStringPermutationGenerator {

    /**
     * Characters that require escaping in Kotlin double-quoted string literals.
     */
    val SPECIAL_CHARACTERS = listOf(
        '"',      // Double quote - terminates string
        '\\',     // Backslash - escape character
        '$',      // Dollar sign - string interpolation
        '\n',     // Newline
        '\r',     // Carriage return
        '\t',     // Tab
        '\b',     // Backspace
    )

    /**
     * Common flow name patterns from MuleSoft that often contain special characters.
     */
    val FLOW_NAME_PATTERNS: List<String> = listOf(
        "get:\\api\\resources",              // APIKit pattern with backslashes
        "post:\\api\\users\\{id}",          // APIKit with path params
        "flow-\"quoted\"-name",             // Flow name with quotes  
        "flow-with-\$variable",            // Flow name with dollar sign
        "flow\\with\\backslash",            // Simple backslash
        "flow\nwith\nnewline",              // Newlines (rare but possible)
        "flow\twith\ttab",                 // Tabs
        "normal-flow-name",                 // Normal case (no special chars)
        "",                                 // Empty string (edge case)
        "\"",                               // Just a quote
        "\\",                               // Just a backslash
        "\$",                               // Just a dollar sign
    )

    /**
     * Generates all permutations of strings by combining base strings with special characters.
     *
     * @param baseStrings List of base strings to use as templates
     * @param specialChars List of special characters to inject
     * @param maxCombinations Maximum number of combinations to generate (prevents explosion)
     * @return List of generated test strings
     */
    fun generatePermutations(
        baseStrings: List<String> = FLOW_NAME_PATTERNS,
        specialChars: List<Char> = SPECIAL_CHARACTERS,
        maxCombinations: Int = 1000
    ): List<String> {
        val results = mutableListOf<String>()

        // Add all base strings as-is
        results.addAll(baseStrings)

        // Generate combinations: base + special at various positions
        for (base in baseStrings) {
            for (char in specialChars) {
                if (results.size >= maxCombinations) break

                // Special character at beginning
                results.add("$char$base")

                // Special character at end
                results.add("$base$char")

                // Special character in middle (if base has content)
                if (base.isNotEmpty() && base.length > 1) {
                    val mid = base.length / 2
                    results.add(base.take(mid) + char + base.drop(mid))
                }

                // Multiple special characters
                if (results.size < maxCombinations) {
                    results.add("$char$char$base")
                    results.add("$base$char$char")
                }
            }
        }

        // Generate combinations of multiple special characters
        for (c1 in specialChars) {
            for (c2 in specialChars) {
                if (results.size >= maxCombinations) break
                results.add("$c1$c2")
                results.add("flow${c1}middle${c2}end")
            }
        }

        return results.distinct().take(maxCombinations)
    }

    /**
     * Generates test cases specifically for flow names that mimic real MuleSoft patterns.
     */
    fun generateFlowNameTestCases(): List<TestCase> {
        val names = listOf(
            // APIKit-style flow names (most common issue)
            "get:\\api\\resources",
            "post:\\api\\users\\{id}",
            "put:\\api\\items\\{itemId}",
            "delete:\\api\\items\\{itemId}",

            // With quotes
            "flow-\"quoted\"-name",
            "\"just-quotes\"",

            // With dollar signs (Kotlin interpolation)
            "flow-with-\$variable",
            "\$dollar-at-start",
            "dollar-at-end\$",

            // Mixed special characters
            "\\\"mixed\\\$quotes\\\"",
            "get:\\api\\\$resources\\test",

            // Edge cases
            "",
            "\\",
            "\"",
            "\$",
            "\\\"\$",

            // Normal cases (should still work)
            "normal-flow",
            "camelCaseFlow",
            "snake_case_flow",
        )

        return names.map { name ->
            TestCase(
                input = name,
                expectedEscaped = escapeManually(name),
                description = "Flow name: ${name.take(50)}${if (name.length > 50) "..." else ""}"
            )
        }
    }

    /**
     * Manual escaping function for verification purposes.
     * Mirrors the logic in CodegenContext.escapeKotlinString().
     */
    private fun escapeManually(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
    }

    /**
     * Validates that all test cases can be properly escaped and used in Kotlin code.
     */
    fun validateTestCases(ctx: CodegenContext): ValidationResult {
        val testCases = generateFlowNameTestCases()
        val failures = mutableListOf<String>()

        for (testCase in testCases) {
            try {
                val escaped = ctx.escapeKotlinString(testCase.input)

                // Verify escaping worked correctly
                if (testCase.input != testCase.input && escaped == testCase.input) {
                    failures.add("${testCase.description}: Failed to escape")
                }

                // Verify the escaped string doesn't contain unescaped special chars
                val unescapedQuotes = escaped.count { it == '"' } - escaped.count { it == '\\' && escaped.indexOf(it) < escaped.length - 1 && escaped[escaped.indexOf(it) + 1] == '"' } * 2
                if (unescapedQuotes % 2 != 0) {
                    failures.add("${testCase.description}: Unescaped quotes detected")
                }
            } catch (e: Exception) {
                failures.add("${testCase.description}: Exception - ${e.message}")
            }
        }

        return ValidationResult(
            totalTests = testCases.size,
            failures = failures,
            successRate = (testCases.size - failures.size).toDouble() / testCases.size
        )
    }

    data class TestCase(
        val input: String,
        val expectedEscaped: String,
        val description: String
    )

    data class ValidationResult(
        val totalTests: Int,
        val failures: List<String>,
        val successRate: Double
    ) {
        val isSuccess: Boolean get() = failures.isEmpty()

        override fun toString(): String {
            return "ValidationResult(total=$totalTests, passed=${totalTests - failures.size}, " +
                    "failed=${failures.size}, rate=${"%.1f".format(successRate * 100)}%)"
        }
    }
}

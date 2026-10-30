package dev.museframework.connectors.http.spring.fuzz

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.*
import kotlin.random.Random

/**
 * Fuzz testing generators for robustness testing of HTTP endpoints.
 * All generators accept a seed parameter for deterministic, reproducible tests.
 */
object FuzzGenerators {

    /**
     * Generates malformed JSON strings for testing parser robustness.
     * Includes only values rejected by a strict JSON parser: missing braces,
     * raw control bytes, invalid escapes, truncated values, and invalid
     * separators.
     *
     * @param seed Random seed for deterministic generation
     * @return Arb<String> generating malformed JSON payloads
     */
    fun jsonMalformed(seed: Long = 42L): Arb<String> = arbitrary { rs ->
        val random = Random(seed + rs.random.nextLong())
        
        val malformedTypes =
            listOf(
                // Missing opening brace
                """"key": "value"}""",
                // Missing closing brace
                """{"key": "value"""",
                // Raw null byte inside a JSON string
                "{\"key\":\"value\u0000\"}",
                // Invalid single-backslash escape sequence
                """{"key":"\z"}""",
                // Missing value
                """{"key":}""",
                // Trailing unmatched brace
                """{"key": "value"}}""",
                // Trailing comma
                """{"key": "value",}""",
                // Missing colon
                """{"key" "value"}""",
                // Mismatched collection delimiters
                """{"key":[1,2}""",
                // Random content with a guaranteed invalid prefix
                "not-json:" + Arb.string(minSize = 1, maxSize = 1000).bind(),
            )
        malformedTypes.random(random)
    }

    /**
     * Generates invalid HTTP headers for testing header parsing robustness.
     * Includes: malformed Content-Type, control characters, oversized headers.
     *
     * @param seed Random seed for deterministic generation
     * @return Arb<Pair<String, String>> generating (headerName, headerValue) pairs
     */
    fun invalidHeaders(seed: Long = 42L): Arb<Pair<String, String>> = arbitrary { rs ->
        val random = Random(seed + rs.random.nextLong())
        
        val headerNames = listOf(
            "Content-Type",
            "X-Correlation-ID",
            "Authorization",
            "Accept",
            "X-Custom-Header"
        )
        
        val invalidValues = listOf(
            // Control characters in header value
            "application/json\u0000",
            "application/json\u0001",
            "application/json\r\n",
            // Malformed Content-Type
            "application/\\json",
            "application/json; charset=\u0000utf-8",
            // Oversized header (simulate large value)
            "A".repeat(8192),
            // Empty value
            "",
            // Newline injection attempt
            "application/json\nX-Injection: evil",
            // Null byte
            "\u0000",
            // Very long random string
            Arb.string(minSize = 1000, maxSize = 2000).bind()
        )
        
        val name = headerNames.random(random)
        val value = invalidValues.random(random)
        
        name to value
    }

    /**
     * Generates path traversal strings for testing path validation.
     * Includes: ../, ..%2f, unicode variants, null byte injection.
     *
     * @param seed Random seed for deterministic generation
     * @return Arb<String> generating path traversal attempts
     */
    fun pathTraversal(seed: Long = 42L): Arb<String> = arbitrary { rs ->
        val random = Random(seed + rs.random.nextLong())
        
        val basePaths = listOf(
            "/api/hello",
            "/api/users",
            "/static/files",
            "/download"
        )
        
        val traversalPatterns = listOf(
            // Basic traversal
            "../../../etc/passwd",
            "..\\..\\windows\\system32\\config\\sam",
            // URL encoded traversal
            "..%2f..%2f..%2fetc%2fpasswd",
            "..%252f..%252fetc%252fpasswd",
            // Double URL encoding
            "%252e%252e%252fetc%252fpasswd",
            // Unicode traversal
            "..\u2215..\u2215etc\u2215passwd", // \u2215 is ∕ (division slash)
            "..\uff0f..\uff0fetc\uff0ffpasswd", // \uff0f is ／ (fullwidth slash)
            // Null byte injection
            "../../../etc/passwd%00",
            "../../../etc/passwd\u0000",
            // Mixed encoding
            "..%2f..\u2215../etc/passwd",
            // Path with traversal in middle
            "/api/../../../etc/passwd",
            // Windows-style
            "..\\..\\..\\windows\\system.ini",
            // Overlong traversal
            "../".repeat(100) + "etc/passwd"
        )
        
        val basePath = basePaths.random(random)
        val traversal = traversalPatterns.random(random)
        
        // Sometimes append to base path, sometimes use standalone
        if (random.nextBoolean()) {
            basePath + "/" + traversal
        } else {
            traversal
        }
    }

    /**
     * Generates one valid JSON object of the requested size without first
     * allocating larger discarded candidates.
     *
     * @param size exact payload size in characters
     * @param seed random seed for deterministic generation
     */
    fun largeJsonObjectPayload(
        size: Int,
        seed: Long = 42L,
    ): Arb<String> = arbitrary { rs ->
        require(size >= LARGE_JSON_PREFIX.length + LARGE_JSON_SUFFIX.length) {
            "size must fit the JSON object envelope"
        }
        val random = Random(seed + rs.random.nextLong())
        val contentSize = size - LARGE_JSON_PREFIX.length - LARGE_JSON_SUFFIX.length
        val content = CharArray(contentSize) { ALPHANUMERIC.random(random) }.concatToString()

        LARGE_JSON_PREFIX + content + LARGE_JSON_SUFFIX
    }

    private val ALPHANUMERIC = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    private const val LARGE_JSON_PREFIX = "{\"data\":\""
    private const val LARGE_JSON_SUFFIX = "\"}"

    /**
     * Generates random UTF-8 strings with various Unicode characters.
     * Includes: emojis, control characters, combining characters, CJK, RTL.
     *
     * @param seed Random seed for deterministic generation
     * @return Arb<String> generating random UTF-8 strings
     */
    fun randomUtf8(seed: Long = 42L): Arb<String> = arbitrary { rs ->
        val random = Random(seed + rs.random.nextLong())
        
        val charSets = listOf(
            // Basic multilingual plane
            (0x0000..0xFFFF).map { code -> code.toChar() },
            // Emojis and symbols
            listOf(
                "😀", "🎉", "🚀", "💻", "🔥", "✨", "🌟", "💡",
                "👍", "👎", "🤔", "😂", "🥳", "🤯", "🤖", "👾"
            ),
            // Control characters
            (0x0000..0x001F).map { code -> code.toChar() },
            // CJK characters (Chinese, Japanese, Korean)
            (0x4E00..0x9FFF).map { code -> code.toChar() },
            // Arabic/Hebrew (RTL scripts)
            (0x0590..0x05FF).map { code -> code.toChar() },
            // Combining characters (can cause rendering issues)
            (0x0300..0x036F).map { code -> code.toChar() },
            // Mathematical symbols
            (0x2200..0x22FF).map { code -> code.toChar() },
            // Box drawing characters
            (0x2500..0x257F).map { code -> code.toChar() },
            // Private use area (PUA)
            (0xE000..0xF8FF).map { code -> code.toChar() },
            // Surrogate pairs (for characters outside BMP)
            listOf("𐍈", "𠜎", "𡿺", "𣎴", "𤭢", "𥐮", "𦥯", "𧾷", "𨭎", "𩶘")
        )
        
        val selectedCharSet = charSets.random(random)
        val length = random.nextInt(1, 1000)
        
        buildString {
            repeat(length) {
                append(selectedCharSet.random(random))
            }
        }
    }
}

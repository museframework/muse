package dev.museframework.babelfish.emitter

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@DisplayName("CodegenContext.escapeKotlinString()")
class KotlinStringEscapeTest {

    @Test
    fun `escapes backslash correctly`() {
        val ctx = CodegenContext("com.test")
        val input = "flow\\with\\backslash"
        val escaped = ctx.escapeKotlinString(input)

        assertEquals("flow\\\\with\\\\backslash", escaped)
        assertEquals(4, escaped.count { it == '\\' })
    }

    @Test
    fun `escapes double quotes correctly`() {
        val ctx = CodegenContext("com.test")
        val input = "flow-\"quoted\"-name"
        val escaped = ctx.escapeKotlinString(input)

        assertEquals("flow-\\\"quoted\\\"-name", escaped)
    }

    @Test
    fun `escapes dollar sign correctly`() {
        val ctx = CodegenContext("com.test")
        val input = "flow-with-\$variable"
        val escaped = ctx.escapeKotlinString(input)

        assertEquals("flow-with-\\\$variable", escaped)
    }

    @Test
    fun `handles APIKit flow names with backslashes`() {
        val ctx = CodegenContext("com.test")
        val input = "get:\\api\\resources:api-spec-config"
        val escaped = ctx.escapeKotlinString(input)

        assertEquals("get:\\\\api\\\\resources:api-spec-config", escaped)
        assertNotEquals(input, escaped)
    }

    @Test
    fun `handles mixed special characters`() {
        val ctx = CodegenContext("com.test")
        val input = "\\\"mixed\\\$chars\\\""
        val escaped = ctx.escapeKotlinString(input)

        assertEquals("\\\\\\\"mixed\\\\\\\$chars\\\\\\\"", escaped)
    }

    @Test
    fun `empty string returns empty string`() {
        val ctx = CodegenContext("com.test")
        assertEquals("", ctx.escapeKotlinString(""))
    }

    @Test
    fun `string without special characters remains unchanged`() {
        val ctx = CodegenContext("com.test")
        val input = "normal-flow-name"
        assertEquals(input, ctx.escapeKotlinString(input))
    }

    @Test
    fun `single backslash becomes double backslash`() {
        val ctx = CodegenContext("com.test")
        assertEquals("\\\\", ctx.escapeKotlinString("\\"))
    }

    @Test
    fun `single quote becomes escaped quote`() {
        val ctx = CodegenContext("com.test")
        assertEquals("\\\"", ctx.escapeKotlinString("\""))
    }

    @Test
    fun `single dollar becomes escaped dollar`() {
        val ctx = CodegenContext("com.test")
        assertEquals("\\\$", ctx.escapeKotlinString("\$"))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "get:\\api\\resources",
        "post:\\api\\users\\{id}",
        "put:\\api\\items\\{itemId}",
        "delete:\\api\\items\\{itemId}",
        "flow-\"quoted\"-name",
        "flow-\$variable",
        ""
    ])
    fun `permutation test - all APIKit patterns can be escaped`(input: String) {
        val ctx = CodegenContext("com.test")
        val escaped = ctx.escapeKotlinString(input)

        if (input.contains(Regex("[\"\\\\\$\n\r\t\b]"))) {
            assertNotEquals(input, escaped, "Input with special chars should be transformed")
        }
    }

    @Test
    fun `backslash must be escaped before quotes to avoid double-escaping`() {
        val ctx = CodegenContext("com.test")
        val input = "\\\""
        val escaped = ctx.escapeKotlinString(input)

        assertEquals("\\\\\\\"", escaped)
    }

    @Test
    fun `complex real-world flow name`() {
        val ctx = CodegenContext("com.test")
        val input = "get:\\api\\resources:api-spec-config"
        val escaped = ctx.escapeKotlinString(input)

        val generatedCode = """flow("$escaped") { }"""

        assertEquals(true, generatedCode.contains("flow(\""))
        assertEquals(true, generatedCode.contains("\")"))
    }
}

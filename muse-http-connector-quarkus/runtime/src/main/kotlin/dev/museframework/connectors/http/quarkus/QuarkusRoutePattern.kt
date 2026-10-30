package dev.museframework.connectors.http.quarkus

import java.nio.charset.StandardCharsets

internal class QuarkusRoutePattern private constructor(
    val regex: String?,
    private val compiledRegex: Regex?,
    private val parameterNames: List<String>,
) {
    fun match(rawPath: String): Map<String, String>? {
        val compiled = compiledRegex ?: return emptyMap()
        val match = compiled.matchEntire(rawPath) ?: return null
        if (parameterNames.isEmpty()) return emptyMap()
        val groups = match.groups
        return buildMap(parameterNames.size) {
            parameterNames.forEachIndexed { index, name ->
                put(name, decodePercentEncodedComponent(checkNotNull(groups[index + 1]).value))
            }
        }
    }

    companion object {
        fun compile(pattern: String): QuarkusRoutePattern {
            if (pattern == "/**" || pattern == "/*") {
                return QuarkusRoutePattern(regex = null, compiledRegex = null, parameterNames = emptyList())
            }

            val parameterNames = mutableListOf<String>()
            val regex = StringBuilder("^")
            var index = 0
            while (index < pattern.length) {
                when {
                    pattern.startsWith("**", index) -> {
                        regex.append(".*")
                        index += 2
                    }
                    pattern[index] == '*' -> {
                        regex.append("[^/]*")
                        index++
                    }
                    pattern[index] == '{' -> {
                        val end = pattern.indexOf('}', index + 1)
                        require(end > index + 1) { "Invalid HTTP route pattern: $pattern" }
                        parameterNames += pattern.substring(index + 1, end)
                        regex.append("([^/]+)")
                        index = end + 1
                    }
                    else -> {
                        val literalStart = index
                        while (
                            index < pattern.length &&
                            pattern[index] != '*' &&
                            pattern[index] != '{'
                        ) {
                            index++
                        }
                        regex.append(Regex.escape(pattern.substring(literalStart, index)))
                    }
                }
            }
            regex.append('$')
            val regexText = regex.toString()
            return QuarkusRoutePattern(regexText, regexText.toRegex(), parameterNames.toList())
        }
    }
}

internal fun decodePercentEncodedComponent(value: String): String {
    if (value.isEmpty() || '%' !in value) return value
    val plusEscaped = value.replace("+", "%2B")
    return try {
        java.net.URLDecoder.decode(plusEscaped, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }
}

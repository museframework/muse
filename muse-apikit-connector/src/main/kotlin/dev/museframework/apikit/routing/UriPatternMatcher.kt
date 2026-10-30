package dev.museframework.apikit.routing

data class PathMatch(
    val matched: Boolean,
    val pathParams: Map<String, String> = emptyMap(),
)

object UriPatternMatcher {
    private val pathParamPattern = Regex("\\{([^}]+)}")

    fun match(
        pattern: String,
        path: String,
    ): PathMatch {
        val normalizedPattern = normalizePath(pattern)
        val normalizedPath = normalizePath(path)

        val patternSegments = splitSegments(normalizedPattern)
        val pathSegments = splitSegments(normalizedPath)

        if (patternSegments.size != pathSegments.size) {
            return PathMatch(matched = false)
        }

        val params = mutableMapOf<String, String>()

        for (i in patternSegments.indices) {
            val patternSegment = patternSegments[i]
            val pathSegment = pathSegments[i]

            val paramName = extractParamName(patternSegment)

            if (paramName != null) {
                if (pathSegment.isEmpty()) {
                    return PathMatch(matched = false)
                }
                params[paramName] = decodeSegment(pathSegment)
            } else {
                if (!segmentsMatch(patternSegment, pathSegment)) {
                    return PathMatch(matched = false)
                }
            }
        }

        return PathMatch(matched = true, pathParams = params)
    }

    fun extractParamNames(pattern: String): List<String> = pathParamPattern.findAll(pattern).map { it.groupValues[1] }.toList()

    private fun normalizePath(path: String): String = path.trimEnd('/')

    private fun splitSegments(path: String): List<String> {
        if (path.isEmpty() || path == "/") return emptyList()
        val withoutLeadingSlash = if (path.startsWith("/")) path.substring(1) else path
        return if (withoutLeadingSlash.isEmpty()) emptyList() else withoutLeadingSlash.split("/")
    }

    private fun extractParamName(segment: String): String? {
        val match = pathParamPattern.matchEntire(segment)
        return match?.groupValues?.get(1)
    }

    private fun segmentsMatch(
        patternSegment: String,
        pathSegment: String,
    ): Boolean = patternSegment == pathSegment

    private fun decodeSegment(segment: String): String = java.net.URLDecoder.decode(segment, "UTF-8")
}

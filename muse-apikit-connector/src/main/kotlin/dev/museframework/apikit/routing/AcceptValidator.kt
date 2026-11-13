package dev.museframework.apikit.routing

import dev.museframework.apikit.api.model.ApiMethod

/** Result of validating Accept against the successful RAML response representations. */
internal enum class AcceptValidation {
    ACCEPTED,
    NOT_ACCEPTABLE,
    MALFORMED,
}

/**
 * Validates only whether a successful response representation is acceptable. It does not select,
 * render, or transform a response. RAML response media types are bare type/subtype values.
 *
 * A bounded forward scanner handles quoted commas, HTTP OWS, parameters, and q-values without
 * regex backtracking. Empty list elements are tolerated as RFC 9110 §5.6.1.2 requires;
 * malformed non-empty elements remain BAD_REQUEST by Muse policy (not an RFC-mandated status).
 * This is a narrow rejection predicate: it never selects or transforms a representation.
 */
internal object AcceptValidator {
    private const val MAX_HEADER_LENGTH = 65_536
    private const val MAX_RANGES = 256
    // RFC 9110 §5.6.1.2: ignore a reasonable number of empty comma-separated list members.
    private const val MAX_LIST_MEMBERS = 512
    private const val MAX_PARAMETERS = 64

    fun validate(method: ApiMethod, headers: Map<String, String>): AcceptValidation {
        // An operation without known successful response bodies imposes no Accept constraint.
        if (method.responseMediaTypes.isEmpty()) return AcceptValidation.ACCEPTED

        val fields = headers.entries.filter { it.key.equals("Accept", ignoreCase = true) }
        if (fields.isEmpty()) return AcceptValidation.ACCEPTED
        var totalLength = 0
        var members = 0
        val ranges = ArrayList<MediaRange>()
        for ((_, header) in fields) {
            if (header.length > MAX_HEADER_LENGTH - totalLength) return AcceptValidation.MALFORMED
            totalLength += header.length
            val parsed = FieldParser(
                header, MAX_RANGES - ranges.size, MAX_LIST_MEMBERS - members, MAX_PARAMETERS,
            ).parse() ?: return AcceptValidation.MALFORMED
            ranges.addAll(parsed.ranges)
            members += parsed.members
        }
        // An empty Accept list imposes no representation preference.
        if (ranges.isEmpty()) return AcceptValidation.ACCEPTED

        for (offered in method.responseMediaTypes) {
            val type = parseBareType(offered) ?: return AcceptValidation.MALFORMED
            var bestSpecificity = -1
            var bestQuality = 0
            for (range in ranges) {
                if (range.mediaParameters > 0 || !range.matches(type)) continue
                if (range.specificity > bestSpecificity) {
                    bestSpecificity = range.specificity
                    bestQuality = range.quality
                } else if (range.specificity == bestSpecificity && range.quality > bestQuality) {
                    bestQuality = range.quality
                }
            }
            if (bestSpecificity >= 0 && bestQuality > 0) return AcceptValidation.ACCEPTED
        }
        return AcceptValidation.NOT_ACCEPTABLE
    }

    private data class BareType(val type: String, val subtype: String)

    private data class MediaRange(
        val type: String,
        val subtype: String,
        val quality: Int,
        val mediaParameters: Int,
    ) {
        val specificity: Int = when {
            type == "*" -> 0
            subtype == "*" -> 1
            else -> 2
        }

        fun matches(offered: BareType): Boolean =
            (type == "*" || type == offered.type) &&
                (subtype == "*" || subtype == offered.subtype)
    }

    private fun parseBareType(value: String): BareType? {
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.lastIndex || value.indexOf('/', slash + 1) >= 0) return null
        val type = value.substring(0, slash)
        val subtype = value.substring(slash + 1)
        if (type.any { !it.isTokenChar() } || subtype.any { !it.isTokenChar() } ||
            '*' in type || '*' in subtype
        ) return null
        return BareType(type.lowercase(), subtype.lowercase())
    }

    private data class ParsedField(val ranges: List<MediaRange>, val members: Int)

    private class FieldParser(
        private val value: String,
        private val remainingRanges: Int,
        private val remainingMembers: Int,
        private val maxParameters: Int,
    ) {
        private var index = 0

        fun parse(): ParsedField? {
            val result = ArrayList<MediaRange>()
            var members = 0
            while (true) {
                if (++members > remainingMembers) return null
                skipOws()
                // Empty elements, including leading/trailing commas, are tolerated by recipients.
                if (index < value.length && value[index] != ',') {
                    if (result.size >= remainingRanges) return null
                    val type = token()?.lowercase() ?: return null
                    if (!consume('/')) return null
                    val subtype = token()?.lowercase() ?: return null
                    if (type.contains('*') && type != "*" || subtype.contains('*') && subtype != "*" ||
                        type == "*" && subtype != "*"
                    ) return null

                    var quality = 1000
                    var foundQuality = false
                    var mediaParameters = 0
                    var parameterCount = 0
                    while (true) {
                        skipOws()
                        if (!consume(';')) break
                        skipOws()
                        if (++parameterCount > maxParameters) return null
                        // RFC 9110 §5.6.6 allows empty parameter productions after a semicolon.
                        if (index == value.length || value[index] == ';' || value[index] == ',') continue
                        val name = token()?.lowercase() ?: return null
                        if (!consume('=')) return null
                        val parameter = parameterValue() ?: return null
                        if (name == "q") {
                            if (foundQuality || parameter.quoted) return null
                            quality = parseQuality(parameter.value) ?: return null
                            foundQuality = true
                        } else {
                            // A media parameter (even if after q) requires a matching offered
                            // representation parameter. ApiMethod currently offers bare types only.
                            mediaParameters++
                        }
                    }
                    result.add(MediaRange(type, subtype, quality, mediaParameters))
                }
                skipOws()
                if (index == value.length) return ParsedField(result, members)
                if (!consume(',')) return null
            }
        }

        private fun token(): String? {
            val start = index
            while (index < value.length && value[index].isTokenChar()) index++
            return if (index == start) null else value.substring(start, index)
        }

        private data class ParameterValue(val value: String, val quoted: Boolean)

        private fun parameterValue(): ParameterValue? {
            if (index == value.length) return null
            if (value[index] != '"') return token()?.let { ParameterValue(it, false) }
            index++
            val result = StringBuilder()
            while (index < value.length) {
                val char = value[index++]
                when {
                    char == '"' -> return ParameterValue(result.toString(), true)
                    char == '\\' -> {
                        if (index == value.length || !value[index].isQuotedPairChar()) return null
                        result.append(value[index++])
                    }
                    char.isQuotedText() -> result.append(char)
                    else -> return null
                }
            }
            return null
        }

        private fun consume(char: Char): Boolean =
            if (index < value.length && value[index] == char) { index++; true } else false

        private fun skipOws() {
            while (index < value.length && (value[index] == ' ' || value[index] == '\t')) index++
        }
    }

    private fun parseQuality(value: String): Int? {
        if (value.isEmpty() || value.length > 5) return null
        if (value[0] != '0' && value[0] != '1') return null
        if (value.length == 1) return if (value[0] == '1') 1000 else 0
        if (value[1] != '.' || value.length > 5) return null
        val digits = value.substring(2)
        if (digits.any { it !in '0'..'9' }) return null
        if (value[0] == '1' && digits.any { it != '0' }) return null
        return if (value[0] == '1') 1000 else digits.padEnd(3, '0').toIntOrNull() ?: 0
    }

    private fun Char.isTokenChar(): Boolean =
        this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z' || this in "!#$%&'*+-.^_`|~"

    private fun Char.isQuotedText(): Boolean =
        this == '\t' || this == ' ' || this == '!' || this in '#'..'[' || this in ']'..'~' || this in '\u0080'..'\u00FF'

    private fun Char.isQuotedPairChar(): Boolean =
        this == '\t' || this in ' '..'~' || this in '\u0080'..'\u00FF'
}

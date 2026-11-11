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
 * regex backtracking. Malformed headers are distinguished from a valid but unacceptable header.
 * This is not a general-purpose HTTP content negotiation implementation.
 */
internal object AcceptValidator {
    private const val MAX_HEADER_LENGTH = 65_536
    private const val MAX_RANGES = 256
    private const val MAX_PARAMETERS = 64

    fun validate(method: ApiMethod, headers: Map<String, String>): AcceptValidation {
        // An operation without known successful response bodies imposes no Accept constraint.
        if (method.responseMediaTypes.isEmpty()) return AcceptValidation.ACCEPTED

        val fields = headers.entries.filter { it.key.equals("Accept", ignoreCase = true) }
        if (fields.isEmpty()) return AcceptValidation.ACCEPTED
        var totalLength = 0
        val ranges = ArrayList<MediaRange>()
        for ((_, header) in fields) {
            totalLength += header.length
            if (totalLength > MAX_HEADER_LENGTH) return AcceptValidation.MALFORMED
            // Mule APIKit 1.11.11 treats absent/empty Accept as */*.
            if (header.all { it == ' ' || it == '\t' }) continue
            val parsed = FieldParser(header, MAX_RANGES - ranges.size, MAX_PARAMETERS).parse()
                ?: return AcceptValidation.MALFORMED
            ranges.addAll(parsed)
        }
        if (ranges.isEmpty()) return AcceptValidation.ACCEPTED

        // Conflicting quality values for identical ranges have no unambiguous preference.
        val qualities = HashMap<String, Int>()
        for (range in ranges) {
            val previous = qualities.putIfAbsent(range.key, range.quality)
            if (previous != null && previous != range.quality) return AcceptValidation.MALFORMED
        }

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
        val key: String,
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

    private class FieldParser(
        private val value: String,
        private val remainingRanges: Int,
        private val maxParameters: Int,
    ) {
        private var index = 0

        fun parse(): List<MediaRange>? {
            val result = ArrayList<MediaRange>()
            while (true) {
                skipOws()
                if (index == value.length || result.size >= remainingRanges) return null
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
                val mediaParameterKey = StringBuilder()
                while (true) {
                    skipOws()
                    if (!consume(';')) break
                    skipOws()
                    parameterCount++
                    if (parameterCount > maxParameters) return null
                    val name = token()?.lowercase() ?: return null
                    val hasEquals = consume('=')
                    if (!hasEquals && !foundQuality) return null
                    val parameterValue = if (hasEquals) parameterValue() ?: return null else null
                    if (name == "q") {
                        if (foundQuality || !hasEquals || parameterValue!!.quoted) return null
                        quality = parseQuality(parameterValue.value) ?: return null
                        foundQuality = true
                    } else if (!foundQuality) {
                        mediaParameters++
                        // Preserve the parameter identity to detect conflicting duplicate ranges.
                        mediaParameterKey.append(';').append(name).append('=').append(parameterValue!!.value)
                    }
                }
                result.add(
                    MediaRange(type, subtype, quality, mediaParameters, "$type/$subtype$mediaParameterKey"),
                )
                skipOws()
                if (index == value.length) return result
                if (!consume(',')) return null
                // Deliberately reject empty list members and trailing commas as malformed.
                skipOws()
                if (index == value.length || value[index] == ',') return null
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

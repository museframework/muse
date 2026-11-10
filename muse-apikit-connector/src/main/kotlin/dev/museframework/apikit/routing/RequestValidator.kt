package dev.museframework.apikit.routing

import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiParameter

object RequestValidator {
    /**
     * Checks the request's media type against the method's declared request-body types.
     *
     * Headers are case-insensitive; media-type parameters in a valid request header do not
     * restrict an otherwise matching bare RAML type. No request body is read.
     */
    fun acceptsContentType(
        method: ApiMethod,
        headers: Map<String, String>,
    ): Boolean {
        if (method.contentTypes.isEmpty()) return true

        // A map may still contain duplicate names with different capitalization.
        val header = headers.entries.singleOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: return false
        val requestedType = parseMediaType(header) ?: return false
        return method.contentTypes.any { it.equals(requestedType, ignoreCase = true) }
    }

    fun validateHeaders(
        method: ApiMethod,
        headers: Map<String, String>,
    ): List<String> =
        method.requiredHeaders
            .filter { it.required }
            .map { it.name }
            .filter { headerName -> !headers.containsKey(headerName) }

    fun validateQueryParams(
        method: ApiMethod,
        queryParams: Map<String, String>,
    ): List<String> =
        method.requiredQueryParams
            .filter { it.required }
            .map { it.name }
            .filter { paramName -> !queryParams.containsKey(paramName) }
}

/**
 * Validates the Content-Type field grammar (RFC 9110 §§5.6.2–5.6.6, 8.3.1) without
 * interpreting parameters. A single forward scan avoids regex stack growth for long values.
 * Returns only type/subtype because B2 matches bare RAML media types.
 */
private fun parseMediaType(value: String): String? {
    var index = skipOws(value, 0)
    val typeStart = index
    index = tokenEnd(value, index)
    if (index == typeStart || index == value.length || value[index] != '/') return null

    val subtypeStart = index + 1
    index = tokenEnd(value, subtypeStart)
    if (index == subtypeStart) return null
    val mediaTypeEnd = index

    while (true) {
        index = skipOws(value, index)
        if (index == value.length) return value.substring(typeStart, mediaTypeEnd)
        if (value[index] != ';') return null
        index = skipOws(value, index + 1)

        // RFC 9110 permits an absent parameter after a semicolon.
        if (index == value.length || value[index] == ';') continue

        val nameStart = index
        index = tokenEnd(value, index)
        if (index == nameStart || index == value.length || value[index] != '=') return null
        index++ // No whitespace is permitted on either side of '='.
        if (index == value.length) return null

        if (value[index] == '"') {
            index = quotedStringEnd(value, index)
            if (index < 0) return null
        } else {
            val parameterValueStart = index
            index = tokenEnd(value, index)
            if (index == parameterValueStart) return null
        }
    }
}

private fun skipOws(value: String, start: Int): Int {
    var index = start
    while (index < value.length && (value[index] == ' ' || value[index] == '\t')) index++
    return index
}

private fun tokenEnd(value: String, start: Int): Int {
    var index = start
    while (index < value.length && isTokenChar(value[index])) index++
    return index
}

private fun isTokenChar(character: Char): Boolean =
    character in '0'..'9' ||
        character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in "!#$%&'*+-.^_`|~"

private fun quotedStringEnd(value: String, start: Int): Int {
    var index = start + 1 // Opening DQUOTE is already verified.
    while (index < value.length) {
        val character = value[index++]
        when {
            character == '"' -> return index
            character == '\\' -> {
                if (index == value.length || !isQuotedPairChar(value[index])) return -1
                index++
            }
            character == '\t' || character == ' ' || character == '!' ||
                character in '#'..'[' || character in ']'..'~' || isObsText(character) -> Unit
            else -> return -1
        }
    }
    return -1 // Missing closing DQUOTE.
}

private fun isQuotedPairChar(character: Char): Boolean =
    character == '\t' || character in ' '..'~' || isObsText(character)

private fun isObsText(character: Char): Boolean = character in '\u0080'..'\u00FF'

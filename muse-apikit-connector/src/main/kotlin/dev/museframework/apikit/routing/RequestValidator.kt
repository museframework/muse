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
        if (!mediaTypeHeader.matches(header.trim())) return false

        val requestedType = header.substringBefore(';').trim()
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

// HTTP token and quoted-string grammar, restricted to Content-Type header syntax.
private const val HTTP_TOKEN = """[!#\$%&'*+.^_\x60|~0-9A-Za-z-]+"""
private val mediaTypeHeader = Regex(
    """$HTTP_TOKEN/$HTTP_TOKEN(?:\s*;\s*$HTTP_TOKEN\s*=\s*(?:$HTTP_TOKEN|"(?:[\x20-\x21\x23-\x5B\x5D-\x7E]|\\[\x20-\x7E])*"))*\s*""",
)

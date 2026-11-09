package dev.museframework.apikit.routing

import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiParameter

object RequestValidator {
    /**
     * RAML request-body media types are constraints on incoming requests. Empty lists
     * mean that the specification declares no media-type restriction.
     */
    fun acceptsContentType(method: ApiMethod, headers: Map<String, String>): Boolean {
        if (method.contentTypes.isEmpty()) return true
        val supplied = headers.entries.firstOrNull {
            it.key.equals("Content-Type", ignoreCase = true)
        }?.value?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return method.contentTypes.any { expected ->
            val type = expected.substringBefore(';').trim().lowercase()
            type == supplied || type == "*/*" ||
                (type.endsWith("/*") && supplied.startsWith(type.substringBefore('/') + "/"))
        }
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

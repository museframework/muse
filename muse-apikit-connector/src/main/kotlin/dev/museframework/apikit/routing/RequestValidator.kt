package dev.museframework.apikit.routing

import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiParameter

object RequestValidator {
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

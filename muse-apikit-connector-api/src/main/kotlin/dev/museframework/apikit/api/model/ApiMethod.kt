package dev.museframework.apikit.api.model

import dev.museframework.http.api.model.HttpMethod

data class ApiMethod(
    val method: HttpMethod,
    val contentTypes: List<String> = emptyList(),
    val requiredHeaders: List<ApiParameter> = emptyList(),
    val requiredQueryParams: List<ApiParameter> = emptyList(),
)

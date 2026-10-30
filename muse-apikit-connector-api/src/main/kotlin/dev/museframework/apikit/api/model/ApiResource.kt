package dev.museframework.apikit.api.model

data class ApiResource(
    val path: String,
    val pathParameters: List<ApiParameter> = emptyList(),
    val methods: List<ApiMethod>,
)

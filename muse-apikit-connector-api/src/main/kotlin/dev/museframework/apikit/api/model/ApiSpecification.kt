package dev.museframework.apikit.api.model

data class ApiSpecification(
    val resources: List<ApiResource>,
    val basePath: String = "/",
)

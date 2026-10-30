package dev.museframework.http.api.model

data class HttpResponseAttributes(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val reasonPhrase: String = "",
)

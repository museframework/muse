package dev.museframework.http.api.model

enum class HttpProtocol(
    val scheme: String,
    val defaultPort: Int,
) {
    HTTP("http", 80),
    HTTPS("https", 443),
}
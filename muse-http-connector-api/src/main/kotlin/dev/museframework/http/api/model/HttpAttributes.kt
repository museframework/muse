package dev.museframework.http.api.model

import java.security.cert.X509Certificate

data class HttpAttributes(
    val method: HttpMethod,
    val requestPath: String,
    val queryParams: Map<String, String>,
    val headers: Map<String, String>,
    val requestUri: String,
    val remoteAddress: String,
    val listenerPath: String = requestPath,
    val relativePath: String = requestPath,
    val maskedRequestPath: String? = null,
    val rawRequestPath: String = requestPath,
    val rawRequestUri: String = requestUri,
    val queryString: String = "",
    val uriParams: Map<String, String> = emptyMap(),
    val version: String = "",
    val scheme: String = "",
    val localAddress: String = "",
    val clientCertificate: X509Certificate? = null,
)
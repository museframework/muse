package dev.museframework.http.api.model

import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import java.security.cert.X509Certificate
import java.util.Collections.emptyMap

/**
 * Adapter-neutral inbound HTTP request.
 *
 * Provider-aware servers populate [bodyProvider] and leave [body] null. The
 * byte-array field is a legacy compatibility path only. The server adapter owns
 * the provider until the handler returns; returning the exact same provider in
 * [HttpResponse.bodyProvider] transfers ownership to response serialization.
 */
data class HttpRequest(
    val method: HttpMethod,
    val requestPath: String,
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val bodyProvider: MuseCursorStreamProvider? = null,
    val contentType: String? = null,
    val remoteAddress: String = "",
    val rawRequestPath: String = requestPath,
    val requestUri: String = requestPath,
    val rawRequestUri: String = requestUri,
    val queryString: String = "",
    val uriParams: Map<String, String> = emptyMap(),
    val version: String = "",
    val scheme: String = "",
    val localAddress: String = "",
    val clientCertificate: X509Certificate? = null,
)

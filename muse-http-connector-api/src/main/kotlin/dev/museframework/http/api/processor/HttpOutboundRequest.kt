package dev.museframework.http.api.processor

import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.config.HttpStreamingMode

data class HttpOutboundRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val bodyProvider: MuseCursorStreamProvider? = null,
    val contentType: String? = null,
    val responseTimeoutMs: Long? = null,
    val followRedirects: Boolean = true,
    val requestStreamingMode: HttpStreamingMode = HttpStreamingMode.AUTO,
)

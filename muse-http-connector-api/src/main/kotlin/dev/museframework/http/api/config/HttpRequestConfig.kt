package dev.museframework.http.api.config

import dev.museframework.http.api.model.HttpMethod
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.config.TargetConfig
import java.util.UUID

data class HttpRequestConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfig: ConfigRef,
    val path: String = "/",
    val url: String? = null,
    val method: HttpMethod = HttpMethod.GET,
    val headers: Map<String, String> = emptyMap(),
    val headersExpression: String? = null,
    val queryParams: Map<String, String> = emptyMap(),
    val queryParamsExpression: String? = null,
    val uriParams: Map<String, String> = emptyMap(),
    val uriParamsExpression: String? = null,
    val body: String? = null,
    val responseTimeoutMs: Long? = null,
    val followRedirects: Boolean? = null,
    val sendBodyMode: HttpSendBodyMode? = null,
    val requestStreamingMode: HttpStreamingMode? = null,
    val sendCorrelationId: SendCorrelationId? = null,
    val correlationId: String? = null,
    val targetConfig: TargetConfig? = null,
    val responseValidator: ResponseValidator = DefaultResponseValidator(),
) : Processor {
    init {
        require(responseTimeoutMs == null || responseTimeoutMs >= 0) {
            "responseTimeoutMs must be >= 0"
        }
    }
}

enum class SendCorrelationId {
    AUTO, ALWAYS, NEVER
}

enum class HttpSendBodyMode {
    AUTO,
    ALWAYS,
    NEVER,
}

enum class HttpStreamingMode {
    AUTO,
    ALWAYS,
    NEVER,
}

package dev.museframework.http.api.dsl

import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.http.api.config.*
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import java.util.*


@MuseDsl
class HttpRequestBuilder(
    private val connectionConfig: ConfigRef,
) {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var path: String = "/"
    var url: String? = null
    var method: HttpMethod = HttpMethod.GET
    private val headers: MutableMap<String, String> = mutableMapOf()
    private val queryParams: MutableMap<String, String> = mutableMapOf()
    private val uriParams: MutableMap<String, String> = mutableMapOf()
    var headersExpression: String? = null
    var queryParamsExpression: String? = null
    var uriParamsExpression: String? = null
    var body: String? = null
    var responseTimeoutMs: Long? = null
    var followRedirects: Boolean? = null
    var sendBodyMode: HttpSendBodyMode? = null
    var requestStreamingMode: HttpStreamingMode? = null
    var sendCorrelationId: SendCorrelationId? = null
    var correlationId: String? = null
    var targetConfig: dev.museframework.dsl.api.config.TargetConfig? = null
    var responseValidator: ResponseValidator = DefaultResponseValidator()

    fun header(name: String, value: String) {
        headers[name] = value
    }

    fun queryParam(name: String, value: String) {
        queryParams[name] = value
    }

    fun uriParam(name: String, value: String) {
        uriParams[name] = value
    }

    fun build(): HttpRequestConfig {
        val callSite = CallSiteCapture.capture()
        return HttpRequestConfig(
            docId = docId,
            docName = docName,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connectionConfig = connectionConfig,
            path = path,
            url = url,
            method = method,
            headers = headers.toMap(),
            headersExpression = headersExpression,
            queryParams = queryParams.toMap(),
            queryParamsExpression = queryParamsExpression,
            uriParams = uriParams.toMap(),
            uriParamsExpression = uriParamsExpression,
            body = body,
            responseTimeoutMs = responseTimeoutMs,
            followRedirects = followRedirects,
            sendBodyMode = sendBodyMode,
            requestStreamingMode = requestStreamingMode,
            sendCorrelationId = sendCorrelationId,
            correlationId = correlationId,
            targetConfig = targetConfig,
            responseValidator = responseValidator,
        )
    }
}

package dev.museframework.http.api.dsl

import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.HttpSendBodyMode
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.config.SendCorrelationId
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import java.util.UUID

@MuseDsl
class HttpRequestConnectionBuilder {
    var docId: String = UUID.randomUUID().toString()
    var name: String = ""
    var docName: String = ""
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var host: String = ""
    var protocol: HttpProtocol = HttpProtocol.HTTP
    var port: Int? = null
    var portExpression: String? = null
    var basePath: String = "/"
    var connectionTimeoutMs: Long = 30000
    var connectionIdleTimeoutMs: Long = 30000
    var responseTimeoutMs: Long = 30000
    var followRedirects: Boolean = true
    var usePersistentConnections: Boolean = true
    var maxConnections: Int = -1
    var enableCookies: Boolean = true
    var sendCorrelationId: SendCorrelationId = SendCorrelationId.AUTO
    var sendBodyMode: HttpSendBodyMode = HttpSendBodyMode.AUTO
    var requestStreamingMode: HttpStreamingMode = HttpStreamingMode.AUTO
    private val defaultHeaders = mutableMapOf<String, String>()
    private val defaultQueryParams = mutableMapOf<String, String>()

    fun defaultHeader(name: String, value: String) {
        defaultHeaders[name] = value
    }

    fun defaultQueryParam(name: String, value: String) {
        defaultQueryParams[name] = value
    }

    fun build(): HttpRequestConnectionConfig {
        val callSite = CallSiteCapture.capture()
        return HttpRequestConnectionConfig(
            name = name,
            docId = docId,
            docName = docName,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            host = host,
            protocol = protocol,
            port = port ?: protocol.defaultPort,
            portExpression = portExpression,
            basePath = basePath,
            connectionTimeoutMs = connectionTimeoutMs,
            connectionIdleTimeoutMs = connectionIdleTimeoutMs,
            responseTimeoutMs = responseTimeoutMs,
            followRedirects = followRedirects,
            usePersistentConnections = usePersistentConnections,
            maxConnections = maxConnections,
            enableCookies = enableCookies,
            defaultHeaders = defaultHeaders.toMap(),
            defaultQueryParams = defaultQueryParams.toMap(),
            sendCorrelationId = sendCorrelationId,
            sendBodyMode = sendBodyMode,
            requestStreamingMode = requestStreamingMode,
        )
    }
}

fun httpRequestConnection(
    block: HttpRequestConnectionBuilder.() -> Unit,
): HttpRequestConnectionConfig =
    HttpRequestConnectionBuilder().apply(block).build()

fun httpRequestConnection(
    name: String,
): ConfigRef =
    ConfigRef.ByName(name)

fun httpRequestConnection(
    config: HttpRequestConnectionConfig,
): ConfigRef =
    ConfigRef.ByReference(config)

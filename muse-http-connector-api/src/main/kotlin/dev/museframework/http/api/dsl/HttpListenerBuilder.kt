package dev.museframework.http.api.dsl

import dev.museframework.http.api.config.*
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import java.util.*


@MuseDsl
class HttpListenerBuilder(
    private val connectionConfigRef: ConnectionConfigRef,
) {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var path: String = "/"
    var allowedMethods: List<HttpMethod> = listOf(HttpMethod.ANY)
    var timeoutMs: Long? = null
    var responseStreamingMode: HttpStreamingMode = HttpStreamingMode.AUTO
    var lifecycle: HttpListenerLifecycle = HttpListenerLifecycle.EAGER
    private var successResponse: HttpResponseConfig = SuccessHttpResponseConfig()
    private var errorResponse: HttpResponseConfig = ErrorHttpResponseConfig()

    fun successResponse(block: HttpResponseConfigBuilder.() -> Unit) {
        successResponse = HttpResponseConfigBuilder().apply(block).build()
    }

    fun errorResponse(block: HttpErrorResponseConfigBuilder.() -> Unit) {
        errorResponse = HttpErrorResponseConfigBuilder().apply(block).build()
    }

    fun build(): HttpListenerConfig {
        val callSite = CallSiteCapture.capture()
        return HttpListenerConfig(
            docId = docId,
            docName = docName,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connectionConfigRef = connectionConfigRef,
            path = path,
            allowedMethods = allowedMethods,
            timeoutMs = timeoutMs,
            successResponse = successResponse,
            errorResponse = errorResponse,
            responseStreamingMode = responseStreamingMode,
            lifecycle = lifecycle,
        )
    }
}
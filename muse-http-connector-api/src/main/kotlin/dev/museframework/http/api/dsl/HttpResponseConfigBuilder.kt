package dev.museframework.http.api.dsl

import dev.museframework.http.api.config.ErrorHttpResponseConfig
import dev.museframework.http.api.config.SuccessHttpResponseConfig
import dev.museframework.dsl.api.annotation.MuseDsl


@MuseDsl
class HttpResponseConfigBuilder {
    var statusCode: String = "#[vars.httpStatus default 200]"
    var reasonPhrase: String? = null
    var headersExpression: String? = null
    var body: String? = null
    private val headers = mutableMapOf<String, String>()

    fun header(name: String, value: String) {
        headers[name] = value
    }

    fun build() = SuccessHttpResponseConfig(
        statusCode = statusCode,
        headers = headers.toMap(),
        body = body,
        reasonPhrase = reasonPhrase,
        headersExpression = headersExpression,
    )
}

@MuseDsl
class HttpErrorResponseConfigBuilder {
    var statusCode: String = "#[vars.httpStatus default 500]"
    var reasonPhrase: String? = null
    var headersExpression: String? = null
    var body: String? = null
    private val headers = mutableMapOf<String, String>()

    fun header(name: String, value: String) {
        headers[name] = value
    }

    fun build() = ErrorHttpResponseConfig(
        statusCode = statusCode,
        headers = headers.toMap(),
        body = body,
        reasonPhrase = reasonPhrase,
        headersExpression = headersExpression,
    )
}
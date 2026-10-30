package dev.museframework.http.api.config

import java.util.Collections.emptyMap


abstract class HttpResponseConfig(
    open val statusCode: String = "#[vars.httpStatus default 200]",
    open val headers: Map<String, String> = emptyMap(),
    open val body: String? = null,
    open val reasonPhrase: String? = null,
    open val headersExpression: String? = null,
)

data class SuccessHttpResponseConfig(
    override val statusCode: String = "#[vars.httpStatus default 200]",
    override val headers: Map<String, String> = emptyMap(),
    override val body: String? = null,
    override val reasonPhrase: String? = null,
    override val headersExpression: String? = null,
) : HttpResponseConfig(statusCode, headers, body, reasonPhrase, headersExpression)

data class ErrorHttpResponseConfig(
    override val statusCode: String = "#[vars.httpStatus default 500]",
    override val headers: Map<String, String> = emptyMap(),
    override val body: String? = null,
    override val reasonPhrase: String? = null,
    override val headersExpression: String? = null,
) : HttpResponseConfig(statusCode, headers, body, reasonPhrase, headersExpression)

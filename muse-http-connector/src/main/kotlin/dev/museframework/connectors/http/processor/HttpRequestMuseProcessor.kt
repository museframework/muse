package dev.museframework.connectors.http.processor

import dev.museframework.dsl.api.model.component.MuseComponent
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.projectTarget
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.HttpSendBodyMode
import dev.museframework.http.api.config.SendCorrelationId
import dev.museframework.http.api.config.ValidationResult
import dev.museframework.http.api.model.HttpErrorType
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.model.HttpResponseAttributes
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.api.processor.HttpOutboundRequest
import kotlinx.coroutines.CancellationException
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private val logger = KotlinLogging.logger {}
private const val HEX_DIGITS = "0123456789ABCDEF"

class HttpRequestMuseProcessor(
    override val component: MuseComponent,
    private val config: HttpRequestConnectionConfig,
    private val requestConfig: HttpRequestConfig,
    private val client: HttpClient,
    private val expressionEvaluator: dev.museframework.engine.api.expression.MuseExpressionEvaluator,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult {
        val response =
            when (val result = processRequest(context)) {
                is ProcessorResult.Failure -> return result
                is ProcessorResult.Success -> result
            }
        val target = requestConfig.targetConfig ?: return response

        // Target defects belong to the projecting caller, not to HTTP transport error translation.
        val projected = context.projectTarget(PolicyOutcome.Success(response.context.event), target, context.event)
        return when (projected) {
            is PolicyOutcome.Success ->
                ProcessorResult.Success(context.withEvent(projected.event))

            is PolicyOutcome.Failure ->
                ProcessorResult.Failure(
                    projected.error,
                    context.withEvent(projected.event.copy(error = projected.error)),
                )
        }
    }

    private suspend fun processRequest(context: ExecutionContext): ProcessorResult =
        try {
            val outboundRequest = buildOutboundRequest(context.event, context)
            logger.debug {
                "[HTTP-REQUEST] Executing ${outboundRequest.method} ${outboundRequest.url} " +
                    "with ${outboundRequest.headers.size} headers and ${outboundRequest.queryParams.size} query parameters"
            }

            val response = client.execute(outboundRequest)
            // Adoption precedes release: the provider is owned by this execution registry before
            // the client's incoming claim is closed, so it is never briefly unowned.
            response.bodyProvider?.let(context.streamingResources::register)
            response.bodyLease?.close()
            logger.debug { "[HTTP-REQUEST] HTTP response received: status=${response.statusCode}, bodySize=${response.body?.size ?: 0} bytes" }
            response.headers.forEach { (key, value) ->
                logger.debug { "[HTTP-REQUEST] Response header: $key = $value" }
            }

            val responseAttributes =
                HttpResponseAttributes(
                    statusCode = response.statusCode,
                    headers = response.headers,
                    reasonPhrase = response.reasonPhrase.orEmpty(),
                )

            when (val validationResult = requestConfig.responseValidator.validate(response, responseAttributes)) {
                is ValidationResult.Success -> {
                    val contentType =
                        response.headers.entries
                            .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
                            ?.value
                            ?: TypedValue.MIME_OCTET_STREAM
                    val responsePayload =
                        response.bodyProvider?.let { provider ->
                            TypedValue.of(provider as Any, contentType)
                        } ?: response.body?.let { body ->
                            if (contentType.startsWith(TypedValue.MIME_JSON)) {
                                TypedValue.of(String(body, Charsets.UTF_8) as Any, TypedValue.MIME_JSON)
                            } else {
                                TypedValue.of(body as Any, contentType)
                            }
                        }
                    val newEvent =
                        context.event.copy(
                            payload = responsePayload,
                            attributes = TypedValue.of(responseAttributes),
                        )

                    logger.debug { "[HTTP-REQUEST] Request successful, creating result..." }
                    ProcessorResult.Success(context.withEvent(newEvent))
                }

                is ValidationResult.Failure -> {
                    val error =
                        MuseError(
                            description = validationResult.description,
                            detailedDescription = "HTTP request failed with status ${response.statusCode}",
                            errorType = validationResult.errorType,
                            cause = RuntimeException("HTTP ${response.statusCode}"),
                            errorMessage = response.errorBodyMessage(),
                        )
                    ProcessorResult.Failure(
                        error = error,
                        context = context.withError(error),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            logger.error(e) { "[HTTP-REQUEST] Request timed out" }
            val error =
                MuseError(
                    description = "HTTP timeout error",
                    detailedDescription = e.message ?: "Request timed out",
                    errorType = HttpErrorType.TIMEOUT,
                    cause = e,
                )
            ProcessorResult.Failure(
                error = error,
                context = context.withError(error),
            )
        } catch (e: UnknownHostException) {
            logger.error(e) { "[HTTP-REQUEST] DNS resolution failed" }
            val error =
                MuseError(
                    description = "HTTP connectivity error",
                    detailedDescription = "DNS resolution failed: ${e.message}",
                    errorType = HttpErrorType.CONNECTIVITY,
                    cause = e,
                )
            ProcessorResult.Failure(
                error = error,
                context = context.withError(error),
            )
        } catch (e: ConnectException) {
            logger.error(e) { "[HTTP-REQUEST] Connection refused" }
            val error =
                MuseError(
                    description = "HTTP connectivity error",
                    detailedDescription = "Connection refused: ${e.message}",
                    errorType = HttpErrorType.CONNECTIVITY,
                    cause = e,
                )
            ProcessorResult.Failure(
                error = error,
                context = context.withError(error),
            )
        } catch (e: SSLException) {
            logger.error(e) { "[HTTP-REQUEST] SSL/TLS error" }
            val error =
                MuseError(
                    description = "HTTP security error",
                    detailedDescription = e.message ?: "SSL/TLS error",
                    errorType = HttpErrorType.SECURITY,
                    cause = e,
                )
            ProcessorResult.Failure(
                error = error,
                context = context.withError(error),
            )
        } catch (e: Exception) {
            logger.error(e) { "[HTTP-REQUEST] Exception during request processing" }
            val error =
                MuseError(
                    description = "HTTP connectivity error",
                    detailedDescription = e.message ?: "Unknown connectivity error",
                    errorType = HttpErrorType.CONNECTIVITY,
                    cause = e,
                )
            ProcessorResult.Failure(
                error = error,
                context = context.withError(error),
            )
        }

    private fun buildOutboundRequest(
        event: MuseEvent,
        context: ExecutionContext,
    ): HttpOutboundRequest {
        val resolvedPath = evaluateString(requestConfig.path, event)
        val resolvedUrl = requestConfig.url?.let { evaluateString(it, event) }
        val resolvedUriParams =
            resolveParameters(
                configured = requestConfig.uriParams,
                expression = requestConfig.uriParamsExpression,
                event = event,
                parameterName = "URI parameters",
            )
        val url = replaceUriParameters(resolvedUrl ?: configuredUrl(resolvedPath), resolvedUriParams)

        val resolvedHeaders =
            resolveParameters(
                defaults = config.defaultHeaders,
                configured = requestConfig.headers,
                expression = requestConfig.headersExpression,
                event = event,
                parameterName = "headers",
                caseInsensitiveNames = true,
            ).toMutableMap()
        val resolvedQueryParams =
            resolveParameters(
                defaults = config.defaultQueryParams,
                configured = requestConfig.queryParams,
                expression = requestConfig.queryParamsExpression,
                event = event,
                parameterName = "query parameters",
            )

        addCorrelationId(resolvedHeaders, event)

        val sendBodyMode = requestConfig.sendBodyMode ?: config.sendBodyMode
        val evaluatedBodyValue =
            requestConfig.body?.let { expressionEvaluator.evaluate(it, event) }
        val bodyValue =
            if (requestConfig.body != null) evaluatedBodyValue?.getOrNull() else event.payload?.getOrNull()
        val shouldSendBody =
            bodyValue != null &&
                bodyValue != "" &&
                when (sendBodyMode) {
                    HttpSendBodyMode.ALWAYS -> true
                    HttpSendBodyMode.NEVER -> false
                    HttpSendBodyMode.AUTO -> requestConfig.method !in EMPTY_BODY_METHODS
                }
        val bodyProvider = (bodyValue as? MuseCursorStreamProvider).takeIf { shouldSendBody }
        bodyProvider?.let(context.streamingResources::register)
        val body = bodyValue?.takeIf { shouldSendBody && bodyProvider == null }?.toHttpBodyBytes()

        val contentType =
            if (bodyProvider != null || body != null) {
                resolvedHeaders.entries
                    .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?: if (requestConfig.body != null) {
                        evaluatedBodyValue?.mimeType
                    } else {
                        event.payload?.mimeType
                    }
            } else {
                null
            }

        return HttpOutboundRequest(
            method = requestConfig.method,
            url = url,
            headers = resolvedHeaders,
            queryParams = resolvedQueryParams,
            body = body,
            bodyProvider = bodyProvider,
            contentType = contentType?.takeUnless { it == TypedValue.MIME_ANY || it == TypedValue.MIME_JAVA },
            responseTimeoutMs = requestConfig.responseTimeoutMs,
            followRedirects = requestConfig.followRedirects ?: config.followRedirects,
            requestStreamingMode = requestConfig.requestStreamingMode ?: config.requestStreamingMode,
        )
    }

    private fun evaluateString(
        value: String,
        event: MuseEvent,
    ): String = expressionEvaluator.evaluate(value, event)?.getOrNull()?.toString() ?: value

    private fun resolveParameters(
        configured: Map<String, String>,
        expression: String?,
        event: MuseEvent,
        parameterName: String,
        defaults: Map<String, String> = emptyMap(),
        caseInsensitiveNames: Boolean = false,
    ): Map<String, String> =
        buildMap(defaults.size + configured.size) {
            fun putParameter(name: String, value: String) {
                if (caseInsensitiveNames) {
                    keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
                }
                put(name, value)
            }

            defaults.forEach { (name, value) -> putParameter(name, evaluateString(value, event)) }
            expression?.let {
                expressionEvaluator.evaluate(it, event)?.getOrNull().toStringMap(parameterName)
                    .forEach { (name, value) -> putParameter(name, value) }
            }
            configured.forEach { (name, value) -> putParameter(name, evaluateString(value, event)) }
        }

    private fun Any?.toStringMap(parameterName: String): Map<String, String> =
        when (this) {
            null -> emptyMap()
            is Map<*, *> ->
                entries.associate { (key, value) ->
                    require(key != null && value != null) { "$parameterName cannot contain null keys or values" }
                    key.toString() to value.toString()
                }
            else -> throw IllegalArgumentException("$parameterName expression must evaluate to a map")
        }

    private fun configuredUrl(path: String): String {
        require(config.host.isNotBlank()) { "host must be configured when request url is not set" }
        return "${config.protocol.scheme}://${config.host.trim()}:${config.port}${joinPaths(config.basePath, path)}"
    }

    private fun joinPaths(
        basePath: String,
        path: String,
    ): String {
        var base = basePath.let { if (it.startsWith('/')) it else "/$it" }
        if (base.endsWith('/') && path.startsWith('/')) base = base.dropLast(1)
        if (!base.endsWith('/') && !path.startsWith('/') && path.isNotEmpty()) base += "/"
        return base + path
    }

    private fun replaceUriParameters(
        value: String,
        uriParams: Map<String, String>,
    ): String = uriParams.entries.fold(value) { resolved, (name, parameter) -> resolved.replace("{$name}", encodePathSegment(parameter)) }

    private fun encodePathSegment(value: String): String =
        buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                if (
                    unsigned in 'A'.code..'Z'.code ||
                    unsigned in 'a'.code..'z'.code ||
                    unsigned in '0'.code..'9'.code ||
                    unsigned == '-'.code || unsigned == '.'.code || unsigned == '_'.code || unsigned == '~'.code
                ) {
                    append(unsigned.toChar())
                } else {
                    append('%')
                    append(HEX_DIGITS[unsigned ushr 4])
                    append(HEX_DIGITS[unsigned and 0xf])
                }
            }
        }

    private fun addCorrelationId(
        headers: MutableMap<String, String>,
        event: MuseEvent,
    ) {
        val strategy = requestConfig.sendCorrelationId ?: config.sendCorrelationId
        if (strategy == SendCorrelationId.NEVER || headers.keys.any { it.equals(CORRELATION_ID_HEADER, ignoreCase = true) }) {
            return
        }
        val correlationId = requestConfig.correlationId?.let { evaluateString(it, event) } ?: event.correlationId
        headers[CORRELATION_ID_HEADER] = correlationId
    }

    private fun Any.toHttpBodyBytes(): ByteArray =
        when (this) {
            is ByteArray -> this
            is String -> toByteArray()
            else -> toString().toByteArray()
        }

    private fun HttpResponse.errorBodyMessage(): String? =
        bodyProvider?.openCursor()?.use(::readBoundedErrorBody)
            ?: body?.let { bytes ->
                val size = minOf(bytes.size, MAX_ERROR_BODY_BYTES)
                String(bytes, 0, size, Charsets.UTF_8) +
                    if (bytes.size > size) TRUNCATED_SUFFIX else ""
            }

    private fun readBoundedErrorBody(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream(minOf(MAX_ERROR_BODY_BYTES, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var truncated = false
        while (output.size() < MAX_ERROR_BODY_BYTES) {
            val remaining = MAX_ERROR_BODY_BYTES - output.size()
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read == -1) return output.toString(Charsets.UTF_8.name())
            output.write(buffer, 0, read)
        }
        truncated = input.read() != -1
        return output.toString(Charsets.UTF_8.name()) + if (truncated) TRUNCATED_SUFFIX else ""
    }

    private companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-ID"
        val EMPTY_BODY_METHODS = setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
        const val TRUNCATED_SUFFIX = " [truncated]"
    }
}

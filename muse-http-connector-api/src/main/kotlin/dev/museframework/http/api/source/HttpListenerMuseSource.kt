package dev.museframework.http.api.source

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.infrastructure.InfrastructureAwareSource
import dev.museframework.engine.api.infrastructure.InfrastructureRequirement
import dev.museframework.engine.api.infrastructure.WebServerRequirement
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpListenerLifecycle
import dev.museframework.http.api.model.HttpAttributes
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.server.HttpListenerServerInitializer
import dev.museframework.http.api.server.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import org.slf4j.LoggerFactory

class HttpListenerMuseSource(
    private val config: HttpListenerConfig,
    private val resolvedConnectionConfig: HttpListenerConnectionConfig,
    private val expressionEvaluator: MuseExpressionEvaluator,
    private val initializer: HttpListenerServerInitializer,
    override val docName: String,
    override val docId: String,
    override val docDescription: String?,
    override val sourceFile: String?,
    override val sourceLine: Int?,
    override val sourceClass: String?,
) : MuseSource,
    InfrastructureAwareSource {
    private val logger = LoggerFactory.getLogger(HttpListenerMuseSource::class.java)
    private var server: HttpServer? = null
    private val listenerPath = joinPaths(resolvedConnectionConfig.basePath, config.path)

    override suspend fun start(channel: SendChannel<MuseEventEnvelope>) {
        server = initializer.initialize(resolvedConnectionConfig)

        server!!.register(listenerPath, config.allowedMethods) { request ->
            val correlationId =
                request.headers.entries
                    .firstOrNull { (name, _) -> name.equals(CORRELATION_ID_HEADER, ignoreCase = true) }
                    ?.value
                    ?: UUID.randomUUID().toString()

            val contentType = request.headers.valueIgnoreCase("Content-Type") ?: "application/octet-stream"
            val payload: TypedValue<Any> =
                request.bodyProvider?.let { TypedValue.of(it as Any, contentType) }
                    ?: TypedValue.of(
                        (request.body?.let { String(it, Charsets.UTF_8) } ?: "") as Any,
                        contentType,
                    )

            val event =
                MuseEvent(
                    payload = payload,
                    attributes =
                        TypedValue.of(
                            HttpAttributes(
                                method = request.method,
                                requestPath = request.requestPath,
                                queryParams = request.queryParams,
                                headers = request.headers,
                                requestUri = request.requestUri,
                                remoteAddress = request.remoteAddress,
                                listenerPath = listenerPath,
                                relativePath = relativePath(request.requestPath),
                                maskedRequestPath = maskedRequestPath(listenerPath, request.rawRequestPath),
                                rawRequestPath = request.rawRequestPath,
                                rawRequestUri = request.rawRequestUri,
                                queryString = request.queryString,
                                uriParams = request.uriParams,
                                version = request.version,
                                scheme = request.scheme,
                                localAddress = request.localAddress,
                                clientCertificate = request.clientCertificate,
                            ),
                        ),
                    correlationId = correlationId,
                )

            // The exported reply claim and the cancellation signal belong to this request only.
            val replyResources = StreamingResourceRegistry()
            val requestCancellation = CompletableDeferred<Unit>()
            val envelope =
                MuseEventEnvelope(
                    event = event,
                    replyResources = replyResources,
                    requestCancellation = requestCancellation,
                )

            try {
                channel.send(envelope)
            } catch (failure: Throwable) {
                // A failed or cancelled send leaves the claims with their sending source.
                releaseQuietly(envelope.executionResources, failure)
                throw failure
            }

            try {
                val resultEvent =
                    if (config.timeoutMs != null) {
                        withTimeoutOrNull(config.timeoutMs) { envelope.replyTo.await() }
                            ?: buildTimeoutEvent(event).also {
                                // Only a timeout or an abort signals request cancellation.
                                requestCancellation.complete(Unit)
                            }
                    } else {
                        envelope.replyTo.await()
                    }

                resultEvent.toHttpResponse(replyResources)
            } catch (cancellation: CancellationException) {
                requestCancellation.complete(Unit)
                throw cancellation
            } finally {
                // The exported provider keeps its lease; everything else registered for the reply
                // is temporary and ends with this registry.
                releaseQuietly(replyResources, null)
            }
        }

        // Handle lifecycle configuration
        when (config.lifecycle) {
            HttpListenerLifecycle.EAGER -> {
                // EAGER: Start server immediately
                server!!.start()
            }

            HttpListenerLifecycle.LAZY -> {
                // LAZY: Server start deferred to infrastructure handler (Task 12b)
                // Handler is registered, but server.start() not called yet
            }
        }
    }

    override suspend fun stop() {
        server?.unregister(listenerPath, config.allowedMethods)
    }

    override suspend fun onResult(
        event: MuseEvent,
        transactionContext: Any?,
    ) {}

    override fun getInfrastructureRequirements(): List<InfrastructureRequirement> = listOf(WebServerRequirement)

    private fun buildTimeoutEvent(originalEvent: MuseEvent): MuseEvent =
        originalEvent.copy(
            variables = originalEvent.variables + ("httpStatus" to TypedValue.of(504)),
            payload = TypedValue.of("Gateway Timeout"),
        )

    /**
     * Builds the adapter-neutral response while [replyResources] is still open, retaining exactly
     * the provider the response exports. The adapter closes that lease after serialization,
     * HEAD/no-body handling, disconnect or response-construction failure.
     */
    private fun MuseEvent.toHttpResponse(replyResources: StreamingResourceRegistry): HttpResponse {
        val responseConfig = if (error != null) config.errorResponse else config.successResponse
        val statusCode =
            expressionEvaluator
                .evaluate(responseConfig.statusCode, this)
                ?.getOrNull()
                ?.toString()
                ?.toIntOrNull()
                ?: if (error != null) 500 else 200
        val reasonPhrase = responseConfig.reasonPhrase?.let { evaluateString(it, this) }

        val evaluatedBody =
            responseConfig.body?.let { expressionEvaluator.evaluate(it, this) }
        val bodyValue =
            if (responseConfig.body != null) {
                evaluatedBody?.getOrNull()
            } else {
                error?.description ?: payload?.getOrNull()
            }
        val bodyProvider = bodyValue as? MuseCursorStreamProvider
        val body = if (bodyProvider == null) bodyValue?.toBytes() else null
        val contentType =
            (if (responseConfig.body != null) evaluatedBody?.mimeType else payload?.mimeType)
                ?: "application/octet-stream"

        val responseHeaders = mutableMapOf<String, String>()
        responseConfig.headersExpression
            ?.let { expressionEvaluator.evaluate(it, this)?.getOrNull().toStringMap("response headers") }
            ?.forEach { (name, value) -> responseHeaders.putHeader(name, value) }
        responseConfig.headers.forEach { (name, value) ->
            responseHeaders.putHeader(name, evaluateString(value, this))
        }
        if (responseHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            responseHeaders["Content-Type"] = contentType
        }

        val bodyLease =
            bodyProvider?.let { exported ->
                // A response expression may create its own provider; register it before retaining.
                replyResources.register(exported)
                replyResources.retain(listOf(exported))
            }

        return HttpResponse(
            statusCode = statusCode,
            headers = responseHeaders,
            body = body,
            bodyProvider = bodyProvider,
            reasonPhrase = reasonPhrase,
            streamingMode = config.responseStreamingMode,
            bodyLease = bodyLease,
        )
    }

    private fun evaluateString(
        value: String,
        event: MuseEvent,
    ): String = expressionEvaluator.evaluate(value, event)?.getOrNull()?.toString() ?: value

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

    private fun MutableMap<String, String>.putHeader(
        name: String,
        value: String,
    ) {
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
        put(name, value)
    }

    private fun Map<String, String>.valueIgnoreCase(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

    private fun relativePath(requestPath: String): String {
        val basePath = resolvedConnectionConfig.basePath.removeSuffix("/")
        return if (basePath.isEmpty() || basePath == "/" || !requestPath.startsWith(basePath)) {
            requestPath
        } else {
            requestPath.substring(basePath.length)
        }
    }

    private fun maskedRequestPath(
        listenerPath: String,
        rawRequestPath: String,
    ): String? {
        if (!listenerPath.endsWith("*")) return null
        val fixedPath = listenerPath.trimEnd('*').removeSuffix("/")
        if (!rawRequestPath.startsWith(fixedPath)) return rawRequestPath
        return rawRequestPath.removePrefix(fixedPath).ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }
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

    /**
     * Cleanup failures never rewrite a reply that was already built: they attach to the failure
     * that is propagating, and are logged when the request itself succeeded.
     */
    private fun releaseQuietly(
        registry: StreamingResourceRegistry,
        failure: Throwable?,
    ) {
        try {
            registry.releaseAll()
        } catch (cleanupFailure: StreamingException) {
            if (failure != null) failure.addSuppressed(cleanupFailure) else
                logger.error("Failed to release temporary streaming resources", cleanupFailure)
        }
    }

    private fun Any.toBytes(): ByteArray =
        when (this) {
            is ByteArray -> this
            is String -> toByteArray()
            else -> toString().toByteArray()
        }

    private companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-ID"
    }
}

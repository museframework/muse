package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamMaximumSizeExceededException
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.api.streaming.StreamingProviders
import dev.museframework.engine.api.streaming.StreamingResourceLease
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.engine.api.streaming.StreamingStrategy
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpRequest
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.model.withOwnedBodyLease
import dev.museframework.http.api.server.HttpServer
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.util.AntPathMatcher
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.util.UriUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

class SpringHttpServer(
    private val streamingStrategy: StreamingStrategy = StreamingStrategy.RepeatableFileStore(),
    private val requestResponseSpoolingStrategy: StreamingStrategy.RepeatableFileStore =
        StreamingStrategy.RepeatableFileStore(),
    private val legacyBodyMaterialization: Boolean = false,
) : HttpServer {
    private val logger = LoggerFactory.getLogger(SpringHttpServer::class.java)
    private val handlers = ConcurrentHashMap<String, suspend (HttpRequest) -> HttpResponse>()
    private val pathMatcher = AntPathMatcher()

    init {
        require(!legacyBodyMaterialization || streamingStrategy !is StreamingStrategy.NonRepeatable) {
            "Legacy request-body materialization requires a repeatable streaming strategy"
        }
    }

    override fun register(
        path: String,
        methods: List<HttpMethod>,
        handler: suspend (HttpRequest) -> HttpResponse,
    ) {
        handlers[routeKey(path, methods)] = handler
    }

    override fun unregister(
        path: String,
        methods: List<HttpMethod>,
    ) {
        handlers.remove(routeKey(path, methods))
    }

    override suspend fun start() = Unit // lifecycle managed by Spring

    override suspend fun stop() {
        handlers.clear()
    }

    fun handle(request: ServerRequest): HandlerFunction<ServerResponse>? =
        matchingHandler(request)?.let { match ->
            HandlerFunction {
                try {
                    handleRequest(request, match)
                } catch (failure: StreamMaximumSizeExceededException) {
                    streamingErrorResponse(
                        statusCode = 413,
                        message = "Request body exceeds the configured streaming limit of ${failure.limit} bytes",
                    )
                } catch (failure: StreamingException) {
                    streamingErrorResponse(
                        statusCode = 500,
                        message = "Streaming request failed: ${failure.message}",
                    )
                } catch (failure: IOException) {
                    streamingErrorResponse(
                        statusCode = 500,
                        message = "Unable to read streaming request body: ${failure.message ?: "I/O failure"}",
                    )
                }
            }
        }

    private fun matchingHandler(request: ServerRequest): MatchedHandler? =
        handlers.entries
            .firstNotNullOfOrNull { (key, handler) ->
                val (path, methods) = parseRouteKey(key)
                // MuleSoft's terminal "/*" is a catch-all, while Spring treats it as one segment.
                val normalizedPath =
                    if (path.endsWith("/*") && !path.endsWith("/**")) "$path*" else path
                val pathMatches = pathMatcher.match(normalizedPath, request.path())
                val methodMatches =
                    methods.contains(HttpMethod.ANY) ||
                        methods.any { it.name == request.method().name() }
                if (pathMatches && methodMatches) MatchedHandler(normalizedPath, handler) else null
            }

    /**
     * Runs one request under the adapter's own inbound claim.
     *
     * The inbound provider is created by this adapter, so the adapter owns it through
     * [requestResources] until the request is over: a provider the engine adopts keeps its engine
     * claim, and one that was never adopted is released here. No provider is ever released
     * directly, and the exported response claim is closed by the response itself.
     */
    private fun handleRequest(
        request: ServerRequest,
        match: MatchedHandler,
    ): ServerResponse {
        val requestResources = StreamingResourceRegistry()
        var responseLease: StreamingResourceLease? = null
        var failure: Throwable? = null
        try {
            val museRequest = request.toMuseHttpRequest(match.pattern, requestResources)
            val response =
                detachNonRepeatableRequestResponse(museRequest, runBlocking { match.handler(museRequest) })
                    .withOwnedBodyLease()
            responseLease = response.bodyLease
            // Response-object, header and content-length construction are inside the bracket: a
            // failure here closes the exact lease this response owns.
            return response.toServerResponse()
        } catch (thrown: Throwable) {
            failure = thrown
            closeLeaseAddingSuppressed(responseLease, thrown)
            throw thrown
        } finally {
            releaseInboundResources(requestResources, failure)
        }
    }

    /**
     * The adapter's inbound claim ends with the request. Cleanup failures attach to the failure
     * that is already propagating and are logged otherwise; they never rewrite a built response.
     */
    private fun releaseInboundResources(
        requestResources: StreamingResourceRegistry,
        failure: Throwable?,
    ) {
        try {
            requestResources.releaseAll()
        } catch (cleanupFailure: Throwable) {
            if (failure != null) failure.addSuppressed(cleanupFailure) else
                logger.error("Failed to release inbound request streaming resources", cleanupFailure)
        }
    }

    private fun ServerRequest.toMuseHttpRequest(
        matchedPattern: String,
        requestResources: StreamingResourceRegistry,
    ): HttpRequest {
        // Claim the provider before parsing metadata or materializing its body can fail.
        val bodyProvider = createBodyProvider()
        bodyProvider?.let(requestResources::register)
        val servletRequest = servletRequest()
        val uri = uri()
        val requestPath = uri.path ?: path()
        val rawRequestPath = uri.rawPath ?: requestPath
        val queryString = uri.query.orEmpty()
        val rawQueryString = uri.rawQuery.orEmpty()
        return HttpRequest(
            method = HttpMethod.valueOf(method().name()),
            requestPath = requestPath,
            queryParams = servletRequest.parameterMap.mapValues { it.value.firstOrNull().orEmpty() },
            headers = headers().asHttpHeaders().toSingleValueMap(),
            body =
                if (legacyBodyMaterialization) {
                    bodyProvider?.openCursor()?.use { it.readBytes() }
                } else {
                    null
                },
            bodyProvider = bodyProvider,
            contentType = headers().contentType().map { it.toString() }.orElse(null),
            remoteAddress = formatAddress(servletRequest.remoteAddr, servletRequest.remotePort),
            rawRequestPath = rawRequestPath,
            requestUri = requestPath + queryString.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty(),
            rawRequestUri = rawRequestPath + rawQueryString.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty(),
            queryString = queryString,
            uriParams = pathMatcher.extractUriTemplateVariables(matchedPattern, path())
                .mapValues { (_, value) -> UriUtils.decode(value, Charsets.UTF_8) },
            version = servletRequest.protocol,
            scheme = servletRequest.scheme,
            localAddress = formatAddress(servletRequest.localAddr, servletRequest.localPort),
            clientCertificate =
                (servletRequest.getAttribute(CLIENT_CERTIFICATE_ATTRIBUTE) as? Array<*>)
                    ?.firstOrNull() as? X509Certificate,
        )
    }

    private fun ServerRequest.createBodyProvider(): MuseCursorStreamProvider? {
        val servletRequest = servletRequest()
        if (servletRequest.contentLengthLong == 0L) return null

        val input = nonEmpty(servletRequest.inputStream) ?: return null
        return try {
            // A live request body can only be consumed while the request is connected, so the
            // non-repeatable inbound path carries the background preparation capability.
            if (streamingStrategy is StreamingStrategy.NonRepeatable) {
                StreamingProviders.managed(BackgroundRequestStreamProvider(input, requestResponseSpoolingStrategy))
            } else {
                StreamingProviders.byteProvider(input, streamingStrategy)
            }
        } catch (failure: Throwable) {
            closeAddingSuppressed(input, failure)
            throw failure
        }
    }

    private fun HttpResponse.toServerResponse(): ServerResponse {
        val adapted =
            if (streamingMode == HttpStreamingMode.NEVER && bodyProvider != null) {
                materializeBodyProvider()
            } else {
                this
            }

        if (
            adapted.streamingMode == HttpStreamingMode.ALWAYS &&
            (adapted.bodyProvider != null || adapted.body != null)
        ) {
            return StreamingServerResponse(adapted, forceChunked = true)
        }
        adapted.bodyProvider?.let {
            return StreamingServerResponse(adapted, forceChunked = false)
        }

        val builder =
            ServerResponse
                .status(adapted.statusCode)
                .headers { springHeaders ->
                    adapted.headers.forEach { (name, value) -> springHeaders.set(name, value) }
                }
        return adapted.body?.let { builder.body(it) } ?: builder.build()
    }

    private fun HttpResponse.materializeBodyProvider(): HttpResponse {
        val provider = checkNotNull(bodyProvider)
        val bytes =
            try {
                provider.openCursor().use { it.readBytes() }
            } catch (failure: Throwable) {
                closeLeaseAddingSuppressed(bodyLease, failure)
                throw failure
            }
        bodyLease?.close()
        return copy(body = bytes, bodyProvider = null, bodyLease = null)
    }

    /**
     * Servlet HTTP/1.x cannot safely echo a still-open request stream directly
     * as its response: a sufficiently large full-duplex exchange can deadlock
     * when both socket buffers fill. Detach that one special case to the
     * configured file store before response headers are written.
     */
    private fun detachNonRepeatableRequestResponse(
        request: HttpRequest,
        response: HttpResponse,
    ): HttpResponse {
        val requestProvider = request.bodyProvider ?: return response
        if (
            streamingStrategy !is StreamingStrategy.NonRepeatable ||
            response.bodyProvider !== requestProvider
        ) {
            return response
        }

        val detachedProvider =
            try {
                requestProvider.openCursor().use { cursor ->
                    StreamingProviders.byteProvider(cursor, requestResponseSpoolingStrategy)
                }
            } catch (failure: Throwable) {
                closeLeaseAddingSuppressed(response.bodyLease, failure)
                throw failure
            }

        // The detached provider owns its claim before the superseded claim is closed, so it is
        // never unowned and a later response-construction failure still closes exactly one lease.
        val detachedClaim = StreamingResourceRegistry.ownedClaim(detachedProvider)
        try {
            // The superseded claim ends before the response is published; a cleanup failure must
            // not lose the claim the detached provider already holds.
            response.bodyLease?.close()
        } catch (failure: Throwable) {
            closeLeaseAddingSuppressed(detachedClaim, failure)
            throw failure
        }
        return response.copy(bodyProvider = detachedProvider, bodyLease = detachedClaim)
    }

    private fun streamingErrorResponse(
        statusCode: Int,
        message: String,
    ): ServerResponse =
        ServerResponse
            .status(statusCode)
            .header(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8")
            .body(message)

    private fun nonEmpty(input: InputStream): InputStream? {
        val pushback = PushbackInputStream(input, 1)
        val first =
            try {
                pushback.read()
            } catch (failure: Throwable) {
                closeAddingSuppressed(pushback, failure)
                throw failure
            }
        if (first == -1) {
            pushback.close()
            return null
        }
        pushback.unread(first)
        return pushback
    }

    private fun formatAddress(
        host: String,
        port: Int,
    ): String =
        when {
            host.isBlank() -> ""
            ':' in host -> "[$host]:$port"
            else -> "$host:$port"
        }

    private fun routeKey(
        path: String,
        methods: List<HttpMethod>,
    ) = "$path|${methods.joinToString(",")}"

    private fun parseRouteKey(key: String): Pair<String, List<HttpMethod>> {
        val (path, methodsPart) = key.split("|")
        return path to methodsPart.split(",").map { HttpMethod.valueOf(it) }
    }

    private data class MatchedHandler(
        val pattern: String,
        val handler: suspend (HttpRequest) -> HttpResponse,
    )

    private fun closeLeaseAddingSuppressed(
        lease: StreamingResourceLease?,
        failure: Throwable,
    ) {
        try {
            lease?.close()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun closeAddingSuppressed(
        input: InputStream,
        failure: Throwable,
    ) {
        try {
            input.close()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private class StreamingServerResponse(
        private val response: HttpResponse,
        private val forceChunked: Boolean,
    ) : ServerResponse {
        private val provider = response.bodyProvider
        private val lease =
            if (provider != null) {
                checkNotNull(response.bodyLease) { "A streaming response must carry its retained claim" }
            } else {
                response.bodyLease
            }
        private val status = HttpStatusCode.valueOf(response.statusCode)
        private val responseHeaders =
            HttpHeaders().apply {
                response.headers.forEach { (name, value) -> set(name, value) }
                if (forceChunked && response.statusCode !in NO_BODY_STATUS_CODES) {
                    remove(HttpHeaders.CONTENT_LENGTH)
                    set(HttpHeaders.TRANSFER_ENCODING, "chunked")
                } else if (
                    response.statusCode !in NO_BODY_STATUS_CODES &&
                    provider != null &&
                    provider.size >= 0 &&
                    contentLength < 0
                ) {
                    contentLength = provider.size
                }
            }

        override fun statusCode(): HttpStatusCode = status

        override fun headers(): HttpHeaders = responseHeaders

        override fun cookies(): MultiValueMap<String, Cookie> = LinkedMultiValueMap()

        override fun writeTo(
            request: HttpServletRequest,
            servletResponse: HttpServletResponse,
            context: ServerResponse.Context,
        ): ModelAndView? {
            try {
                servletResponse.status = response.statusCode
                responseHeaders.forEach { name, values ->
                    values.forEach { value -> servletResponse.addHeader(name, value) }
                }
                if (request.method != "HEAD" && response.statusCode !in NO_BODY_STATUS_CODES) {
                    val body = response.body
                    if (provider != null) {
                        provider.openCursor().use { cursor ->
                            cursor.copyTo(servletResponse.outputStream)
                        }
                    } else if (body != null) {
                        servletResponse.outputStream.write(body)
                    }
                    servletResponse.outputStream.flush()
                }
            } catch (failure: Throwable) {
                try {
                    lease?.close()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
            // Serialization, HEAD and no-body handling all end the exported claim here.
            lease?.close()
            return null
        }

        private companion object {
            val NO_BODY_STATUS_CODES = (100..199).toSet() + setOf(204, 304)
        }
    }

    private companion object {
        const val CLIENT_CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate"
    }
}

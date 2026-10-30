package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingProviders
import dev.museframework.engine.api.streaming.StreamingStrategy
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.api.processor.HttpOutboundRequest
import org.springframework.http.HttpMethod as SpringHttpMethod
import org.springframework.http.StreamingHttpOutputMessage
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP client implementation using Spring's synchronous RestClient.
 *
 * This implementation provides a synchronous, blocking HTTP client that integrates
 * with Muse's coroutine-based execution model. Spring Boot 4.0+ automatically
 * handles virtual threads, making this approach efficient even within suspend functions.
 *
 * @param config The connection configuration containing timeout settings and default behaviors
 */
class SpringHttpClient(
    private val config: HttpRequestConnectionConfig,
    private val streamingStrategy: StreamingStrategy = StreamingStrategy.RepeatableFileStore(),
) : HttpClient {

    private val restClients = ConcurrentHashMap<ClientSettings, RestClient>()
    private val cookieManager =
        if (config.enableCookies) CookieManager(null, CookiePolicy.ACCEPT_ALL) else null
    private val connectionPermits =
        config.maxConnections.takeIf { it > 0 }?.let { Semaphore(it, true) }

    override suspend fun execute(request: HttpOutboundRequest): HttpResponse {
        val permit = connectionPermits
        permit?.acquire()
        var releasePermit = permit != null
        try {
            val method = mapMethod(request.method)
            val uri = buildUri(request)
            val restClient = restClientFor(request)

            val requestSpec =
                restClient
                    .method(method)
                    .uri(uri)
                    .headers { headers ->
                        request.headers.forEach { (key, value) -> headers.add(key, value) }
                        if (request.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                            request.contentType?.let { headers.add("Content-Type", it) }
                        }
                        if (
                            !config.usePersistentConnections &&
                            request.headers.keys.none { it.equals("Connection", ignoreCase = true) }
                        ) {
                            headers.connection = listOf("close")
                        }
                        cookieHeaders(uri).forEach { value ->
                            if (request.headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                                headers.add("Cookie", value)
                            }
                        }
                        if (
                            request.requestStreamingMode == HttpStreamingMode.ALWAYS &&
                            (request.bodyProvider != null || request.body != null)
                        ) {
                            headers.remove("Content-Length")
                            headers.set("Transfer-Encoding", "chunked")
                        } else when (request.requestStreamingMode) {
                            HttpStreamingMode.ALWAYS -> Unit
                            HttpStreamingMode.NEVER -> headers.remove("Transfer-Encoding")
                            HttpStreamingMode.AUTO ->
                                request.bodyProvider
                                    ?.size
                                    ?.takeIf { it >= 0 && headers.contentLength < 0 }
                                    ?.let { headers.contentLength = it }
                        }
                    }

            val responseSpec =
                when {
                    request.bodyProvider != null && request.requestStreamingMode == HttpStreamingMode.NEVER -> {
                        val body = request.bodyProvider!!.openCursor().use { it.readBytes() }
                        requestSpec.body(body)
                    }
                    request.bodyProvider != null -> {
                        val provider = request.bodyProvider!!
                        requestSpec.body(
                            StreamingHttpOutputMessage.Body { output ->
                                provider.openCursor().use { cursor -> cursor.copyTo(output) }
                            },
                        )
                    }
                    request.body != null && request.requestStreamingMode == HttpStreamingMode.ALWAYS -> {
                        val body = request.body!!
                        requestSpec.body(StreamingHttpOutputMessage.Body { output -> output.write(body) })
                    }
                    request.body != null -> requestSpec.body(request.body!!)
                    else -> requestSpec
                }

            val deferResponseClose = streamingStrategy is StreamingStrategy.NonRepeatable
            val response =
                checkNotNull(
                    responseSpec.exchange(
                        { _, clientResponse -> toHttpResponse(request.method, uri, clientResponse, deferResponseClose, permit) },
                        !deferResponseClose,
                    ),
                ) { "Spring RestClient returned no response" }

            val responseBodyProvider = response.bodyProvider
            if (permit != null && deferResponseClose && responseBodyProvider != null) {
                releasePermit = false
            }
            return response
        } finally {
            if (releasePermit) permit?.release()
        }
    }

    override fun close() {
        restClients.clear()
        cookieManager?.cookieStore?.removeAll()
    }

    private fun restClientFor(request: HttpOutboundRequest): RestClient {
        val settings =
            ClientSettings(
                responseTimeoutMs = request.responseTimeoutMs ?: config.responseTimeoutMs,
                followRedirects = request.followRedirects,
            )

        return restClients.getOrPut(settings) {
            val requestFactory =
                RedirectAwareRequestFactory(settings.followRedirects).apply {
                    setConnectTimeout(Duration.ofMillis(config.connectionTimeoutMs))
                    setReadTimeout(Duration.ofMillis(settings.responseTimeoutMs))
                }

            RestClient
                .builder()
                .requestFactory(requestFactory)
                .build()
        }
    }

    private fun toHttpResponse(
        requestMethod: HttpMethod,
        uri: URI,
        response: ClientHttpResponse,
        deferResponseClose: Boolean,
        permit: Semaphore?,
    ): HttpResponse {
        storeCookies(uri, response)
        val statusCode = response.statusCode.value()
        val reasonPhrase = response.statusText
        val headers = response.headers.toSingleValueMap()
        if (!hasResponseBody(requestMethod, statusCode, response.headers.contentLength)) {
            if (deferResponseClose) response.close()
            return HttpResponse(statusCode = statusCode, headers = headers, reasonPhrase = reasonPhrase)
        }

        val bodyProvider =
            if (deferResponseClose) {
                try {
                    val managedInput = ResponseClosingInputStream(response.body, response)
                    nonEmpty(managedInput, closeWhenEmpty = true)?.let { input ->
                        val provider = StreamingProviders.byteProvider(input, StreamingStrategy.NonRepeatable)
                        if (permit != null) {
                            StreamingProviders.managed(PermitReleasingStreamProvider(provider, permit, managedInput))
                        } else {
                            provider
                        }
                    }
                } catch (failure: Exception) {
                    response.close()
                    throw failure
                }
            } else {
                nonEmpty(response.body, closeWhenEmpty = false)?.let { input ->
                    StreamingProviders.byteProvider(input, streamingStrategy)
                }
            }

        return HttpResponse(
            statusCode = statusCode,
            headers = headers,
            bodyProvider = bodyProvider,
            reasonPhrase = reasonPhrase,
        )
    }

    private fun hasResponseBody(
        requestMethod: HttpMethod,
        statusCode: Int,
        contentLength: Long,
    ): Boolean =
        requestMethod != HttpMethod.HEAD &&
            statusCode !in 100..199 &&
            statusCode != 204 &&
            statusCode != 304 &&
            contentLength != 0L

    private fun nonEmpty(
        input: InputStream,
        closeWhenEmpty: Boolean,
    ): InputStream? {
        val pushback = PushbackInputStream(input, 1)
        val first = pushback.read()
        if (first == -1) {
            if (closeWhenEmpty) pushback.close()
            return null
        }
        pushback.unread(first)
        return pushback
    }

    private fun cookieHeaders(uri: URI): List<String> =
        cookieManager?.let { manager ->
            synchronized(manager) {
                manager.get(uri, emptyMap())["Cookie"].orEmpty()
            }
        }.orEmpty()

    private fun storeCookies(
        uri: URI,
        response: ClientHttpResponse,
    ) {
        cookieManager?.let { manager ->
            synchronized(manager) {
                manager.put(uri, response.headers.headerSet().associate { it.key to it.value })
            }
        }
    }

    private fun mapMethod(method: HttpMethod): SpringHttpMethod = when (method) {
        HttpMethod.GET -> SpringHttpMethod.GET
        HttpMethod.POST -> SpringHttpMethod.POST
        HttpMethod.PUT -> SpringHttpMethod.PUT
        HttpMethod.DELETE -> SpringHttpMethod.DELETE
        HttpMethod.PATCH -> SpringHttpMethod.PATCH
        HttpMethod.HEAD -> SpringHttpMethod.HEAD
        HttpMethod.OPTIONS -> SpringHttpMethod.OPTIONS
        HttpMethod.TRACE -> SpringHttpMethod.TRACE
        HttpMethod.ANY -> SpringHttpMethod.GET
    }

    private fun buildUri(request: HttpOutboundRequest): URI {
        val baseUri = URI.create(request.url)

        return if (request.queryParams.isEmpty()) {
            baseUri
        } else {
            UriComponentsBuilder.fromUri(baseUri)
                .apply {
                    request.queryParams.forEach { (key, value) ->
                        queryParam(UriUtils.encode(key, Charsets.UTF_8), UriUtils.encode(value, Charsets.UTF_8))
                    }
                }
                .build(true)
                .toUri()
        }
    }

    private data class ClientSettings(
        val responseTimeoutMs: Long,
        val followRedirects: Boolean,
    )

    private class PermitReleasingStreamProvider(
        private val delegate: MuseCursorStreamProvider,
        private val permit: Semaphore,
        private val responseInput: InputStream,
    ) : MuseCursorStreamProvider {
        private val released = AtomicBoolean(false)
        private val responseCompleted = AtomicBoolean(false)

        override val isClosed: Boolean
            get() = delegate.isClosed

        override val size: Long
            get() = delegate.size

        override fun openCursor(): MuseCursorStream {
            val cursor = delegate.openCursor()
            return object : MuseCursorStream() {
                override val provider: MuseCursorStreamProvider
                    get() = this@PermitReleasingStreamProvider
                override val position: Long
                    get() = cursor.position
                override val isReleased: Boolean
                    get() = cursor.isReleased

                override fun read(): Int = readCompletingResponse { cursor.read() }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    readCompletingResponse { cursor.read(buffer, offset, length) }

                override fun available(): Int = cursor.available()

                override fun seek(position: Long) {
                    readCompletingResponse {
                        cursor.seek(position)
                        if (cursor.position < position) -1 else 0
                    }
                }

                override fun close() {
                    try {
                        cursor.close()
                    } finally {
                        completeResponse()
                    }
                }

                override fun release() {
                    try {
                        cursor.release()
                    } finally {
                        completeResponse()
                    }
                }
            }
        }

        private inline fun readCompletingResponse(read: () -> Int): Int =
            try {
                read().also { if (it == -1) completeResponse() }
            } catch (failure: IOException) {
                try {
                    completeResponse()
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }

        // Transport capacity ends at consumption; registry ownership of the provider can outlive it.
        private fun completeResponse() {
            if (!responseCompleted.compareAndSet(false, true)) return
            try {
                responseInput.close()
            } finally {
                permit.release()
            }
        }

        override fun close() = delegate.close()

        override fun releaseResources() {
            if (!released.compareAndSet(false, true)) return
            try {
                delegate.releaseResources()
            } finally {
                completeResponse()
            }
        }
    }

    private class RedirectAwareRequestFactory(
        private val followRedirects: Boolean,
    ) : SimpleClientHttpRequestFactory() {
        override fun prepareConnection(
            connection: HttpURLConnection,
            httpMethod: String,
        ) {
            super.prepareConnection(connection, httpMethod)
            connection.instanceFollowRedirects = followRedirects
        }
    }

    private class ResponseClosingInputStream(
        delegate: InputStream,
        private val response: ClientHttpResponse,
    ) : FilterInputStream(delegate) {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var failure: Exception? = null
            try {
                super.close()
            } catch (exception: Exception) {
                failure = exception
            }
            try {
                response.close()
            } catch (closeFailure: Exception) {
                if (failure == null) {
                    failure = closeFailure
                } else {
                    failure.addSuppressed(closeFailure)
                }
            }
            failure?.let { throw it }
        }
    }
}

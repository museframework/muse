package dev.museframework.connectors.http.quarkus

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
import io.vertx.core.Handler
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerResponse
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jboss.logging.Logger
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException

@ApplicationScoped
class QuarkusHttpServer : HttpServer {
    private val logger = Logger.getLogger(QuarkusHttpServer::class.java)

    @Inject
    lateinit var router: Router

    private val lifecycleLock = Any()
    private val routes = mutableMapOf<String, Route>()
    private var streamingStrategy: StreamingStrategy = StreamingStrategy.RepeatableFileStore()
    private var requestResponseSpoolingStrategy: StreamingStrategy.RepeatableFileStore =
        StreamingStrategy.RepeatableFileStore()
    private var legacyBodyMaterialization: Boolean = false
    @Volatile
    private var acceptingRegistrations = true
    @Volatile
    private var coroutineScope = newCoroutineScope()

    override fun register(
        path: String,
        methods: List<HttpMethod>,
        handler: suspend (HttpRequest) -> HttpResponse,
    ) {
        val routePattern = QuarkusRoutePattern.compile(path)
        val key = routeKey(path, methods)
        val route = router.route()
        routePattern.regex?.let { route.pathRegex(it) }
        route.handler(BodyHandler.create())
        route.handler(createVertxHandler(routePattern, methods, handler))
        synchronized(lifecycleLock) {
            if (acceptingRegistrations) {
                routes.put(key, route)?.remove()
            } else {
                route.remove()
            }
        }
        logger.info("Registered HTTP route: $path [$methods]")
    }

    override fun unregister(path: String, methods: List<HttpMethod>) {
        val route = synchronized(lifecycleLock) { routes.remove(routeKey(path, methods)) }
        route?.remove()
        logger.debug("Unregistered route: $path [$methods]")
    }

    override suspend fun start() {
        synchronized(lifecycleLock) {
            if (!coroutineScope.isActive) {
                coroutineScope = newCoroutineScope()
            }
            acceptingRegistrations = true
        }
    }

    override suspend fun stop() {
        val (registeredRoutes, scope) =
            synchronized(lifecycleLock) {
                acceptingRegistrations = false
                routes.values.toList().also { routes.clear() } to coroutineScope
            }
        registeredRoutes.forEach { it.remove() }
        scope.cancel()
    }

    fun configureStreaming(
        streamingStrategy: StreamingStrategy,
        spoolingStrategy: StreamingStrategy.RepeatableFileStore,
        legacyBodyMaterialization: Boolean,
    ) {
        this.streamingStrategy = streamingStrategy
        this.requestResponseSpoolingStrategy = spoolingStrategy
        this.legacyBodyMaterialization = legacyBodyMaterialization
    }

    private fun createVertxHandler(
        routePattern: QuarkusRoutePattern,
        methods: List<HttpMethod>,
        handler: suspend (HttpRequest) -> HttpResponse,
    ): Handler<RoutingContext> = Handler { context ->
        coroutineScope.launch {
            try {
                val uriParams = routePattern.match(context.request().path().orEmpty())
                if (uriParams == null || !isMatchingMethod(context, methods)) {
                    context.next()
                    return@launch
                }
                // The adapter's inbound claim: it owns the request provider until the request is
                // over, so no provider is ever released directly here.
                val requestResources = StreamingResourceRegistry()
                var responseLease: StreamingResourceLease? = null
                try {
                    val museRequest = context.toMuseHttpRequest(uriParams, requestResources)
                    val response =
                        detachNonRepeatableRequestResponse(museRequest, runBlocking { handler(museRequest) })
                            .withOwnedBodyLease()
                    responseLease = response.bodyLease
                    try {
                        // Status, headers and content length are set inside the bracket: a failure
                        // before serialization still closes the exact lease this response owns.
                        sendResponse(context, response)
                    } finally {
                        // Serialization, disconnect and no-body handling all end the exported claim here.
                        response.bodyLease?.close()
                    }
                } catch (failure: Throwable) {
                    closeLeaseAddingSuppressed(responseLease, failure)
                    throw failure
                } finally {
                    releaseInboundResources(requestResources)
                }
            } catch (e: StreamMaximumSizeExceededException) {
                logger.warn("Streaming limit exceeded", e)
                endIfOpen(context, 413, "Request body exceeds limit")
            } catch (e: StreamingException) {
                logger.error("Streaming failure", e)
                endIfOpen(context, 500, "Streaming request failed")
            } catch (e: IOException) {
                logger.error("I/O failure", e)
                endIfOpen(context, 500, "Unable to process HTTP body")
            } catch (e: Exception) {
                logger.error("Error handling request", e)
                endIfOpen(context, 500, "Internal Server Error")
            }
        }
    }

    private fun RoutingContext.clientCertificate(): X509Certificate? =
        try {
            request().sslSession()?.peerCertificates?.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        }

    private fun isMatchingMethod(context: RoutingContext, methods: List<HttpMethod>): Boolean {
        if (methods.contains(HttpMethod.ANY)) return true
        val methodName = context.request().method().name()
        return methods.any { it.name == methodName }
    }

    private fun RoutingContext.toMuseHttpRequest(
        uriParams: Map<String, String>,
        requestResources: StreamingResourceRegistry,
    ): HttpRequest {
        // The adapter's inbound registry claims the provider before anything else can observe it.
        val provider = createBodyProvider()
        provider?.let(requestResources::register)
        return try {
            val rawPath = request().path().orEmpty()
            val rawQuery = request().query().orEmpty()
            val requestPath = decodePercentEncodedComponent(rawPath)
            val decodedQuery = decodePercentEncodedComponent(rawQuery)
            HttpRequest(
                method = HttpMethod.valueOf(request().method().name()),
                requestPath = requestPath,
                queryParams = request().params().entries().associate { it.key to (it.value ?: "") },
                headers = request().headers().entries().associate { it.key to (it.value ?: "") },
                body = if (legacyBodyMaterialization) provider?.openCursor()?.use { it.readBytes() } else null,
                bodyProvider = provider,
                contentType = request().getHeader("Content-Type"),
                remoteAddress = request().remoteAddress()?.host()?.takeIf { it.isNotBlank() } ?: "",
                rawRequestPath = rawPath,
                requestUri = requestPath + decodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty(),
                rawRequestUri = rawPath + rawQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty(),
                queryString = decodedQuery,
                uriParams = uriParams,
                version = when (request().version()) {
                    io.vertx.core.http.HttpVersion.HTTP_1_0 -> "HTTP/1.0"
                    io.vertx.core.http.HttpVersion.HTTP_1_1 -> "HTTP/1.1"
                    io.vertx.core.http.HttpVersion.HTTP_2 -> "HTTP/2.0"
                },
                scheme = request().scheme().orEmpty(),
                localAddress = request().localAddress()?.host()?.takeIf { it.isNotBlank() } ?: "",
                clientCertificate = clientCertificate(),
            )
        } catch (failure: Throwable) {
            // The adapter's inbound registry owns this provider; the request scope releases it.
            throw failure
        }
    }

    private fun RoutingContext.createBodyProvider(): MuseCursorStreamProvider? {
        val buffer = body()?.buffer() ?: return null
        if (buffer.length() == 0) return null
        val input = BufferInputStream(buffer)
        val nonEmpty = nonEmpty(input) ?: return null
        return try {
            StreamingProviders.byteProvider(nonEmpty, streamingStrategy)
        } catch (failure: Throwable) {
            closeAddingSuppressed(nonEmpty, failure)
            throw failure
        }
    }

    private suspend fun sendResponse(
        context: RoutingContext,
        response: HttpResponse,
    ) {
        val method = context.request().method().name().uppercase()
        val statusCode = response.statusCode
        val vertxResponse = context.response().setStatusCode(statusCode)
        response.reasonPhrase?.let { vertxResponse.setStatusMessage(it) }
        response.headers.forEach { (k, v) -> vertxResponse.putHeader(k, v) }

        if (hasNoResponseBody(method, statusCode)) {
            awaitFuture(vertxResponse.end())
            return
        }

        val provider = response.bodyProvider
        val body = response.body
        val mode = response.streamingMode
        val version = context.request().version()
        if (provider != null && mode != HttpStreamingMode.NEVER) {
            return streamProviderBody(vertxResponse, context.request().connection(), provider, mode, version)
        }
        if (provider != null) {
            provider.openCursor().use { cursor ->
                val bytes = cursor.readBytes()
                val size = provider.size
                if (size >= 0) {
                    vertxResponse.putHeader("Content-Length", size.toString())
                }
                awaitFuture(vertxResponse.end(Buffer.buffer(bytes)))
            }
            return
        }
        if (body != null) {
            if (mode == HttpStreamingMode.ALWAYS) {
                if (version == io.vertx.core.http.HttpVersion.HTTP_1_0) {
                    vertxResponse.headers().remove("Transfer-Encoding")
                    vertxResponse.putHeader("Content-Length", body.size.toString())
                } else {
                    vertxResponse.forceChunked(version)
                }
            }
            awaitFuture(vertxResponse.end(Buffer.buffer(body)))
            return
        }
        awaitFuture(vertxResponse.end())
        return
    }

    private fun HttpServerResponse.forceChunked(version: io.vertx.core.http.HttpVersion) {
        headers().remove("Content-Length")
        headers().remove("Transfer-Encoding")
        setChunked(true)
        if (version == io.vertx.core.http.HttpVersion.HTTP_1_1) {
            putHeader("Transfer-Encoding", "chunked")
        }
    }

    /**
     * Streams an AUTO/ALWAYS provider body chunk-by-chunk, awaiting each write
     * for backpressure. The request bracket owns and closes the response lease,
     * including when a mid-stream failure resets the connection.
     */
    private suspend fun streamProviderBody(
        vertxResponse: HttpServerResponse,
        connection: io.vertx.core.http.HttpConnection,
        provider: MuseCursorStreamProvider,
        mode: HttpStreamingMode,
        version: io.vertx.core.http.HttpVersion,
    ) {
        val bodySize = provider.size
        val closeFramed = version == io.vertx.core.http.HttpVersion.HTTP_1_0 && bodySize < 0
        if (version == io.vertx.core.http.HttpVersion.HTTP_1_0) {
            vertxResponse.headers().remove("Transfer-Encoding")
            if (bodySize >= 0) {
                vertxResponse.putHeader("Content-Length", bodySize.toString())
            } else {
                vertxResponse.headers().remove("Content-Length")
                vertxResponse.putHeader("Connection", "close")
            }
        } else if (mode == HttpStreamingMode.ALWAYS || bodySize < 0) {
            vertxResponse.forceChunked(version)
        } else {
            vertxResponse.putHeader("Content-Length", bodySize.toString())
        }
        val cursor = provider.openCursor()
        val scratch = ByteArray(QuarkusPullReadStream.DEFAULT_READ_BUFFER_SIZE)
        try {
            var count = cursor.read(scratch, 0, scratch.size)
            while (count != -1) {
                if (count > 0) {
                    awaitFuture(vertxResponse.write(Buffer.buffer(scratch.copyOfRange(0, count))))
                }
                count = cursor.read(scratch, 0, scratch.size)
            }
            awaitFuture(vertxResponse.end())
            cursor.close()
            if (closeFramed) {
                awaitFuture(connection.close())
            }
            return
        } catch (failure: Throwable) {
            logger.warn("Streaming provider response failed", failure)
            closeAddingSuppressed(cursor, failure)
            runCatching { vertxResponse.reset() }
            return
        }
    }

    private suspend fun awaitFuture(future: io.vertx.core.Future<Void>): Void =
        suspendCancellableCoroutine { cont ->
            future.onComplete { ar ->
                if (ar.succeeded()) cont.resume(ar.result())
                else cont.resumeWithException(ar.cause())
            }
        }

    private fun hasNoResponseBody(method: String, statusCode: Int): Boolean =
        method == "HEAD" || statusCode in 100..199 || statusCode == 204 || statusCode == 304

    private fun detachNonRepeatableRequestResponse(request: HttpRequest, response: HttpResponse): HttpResponse {
        val reqProvider = request.bodyProvider ?: return response
        if (streamingStrategy !is StreamingStrategy.NonRepeatable || response.bodyProvider !== reqProvider) return response
        val detached = try {
            reqProvider.openCursor().use { cursor -> StreamingProviders.byteProvider(cursor, requestResponseSpoolingStrategy) }
        } catch (failure: Throwable) {
            closeLeaseAddingSuppressed(response.bodyLease, failure)
            throw failure
        }
        // The detached provider owns its claim before the superseded claim is closed, so it is
        // never unowned and a later response-construction failure still closes exactly one lease.
        val detachedClaim = StreamingResourceRegistry.ownedClaim(detached)
        try {
            // The superseded claim ends before the response is published; a cleanup failure must
            // not lose the claim the detached provider already holds.
            response.bodyLease?.close()
        } catch (failure: Throwable) {
            closeLeaseAddingSuppressed(detachedClaim, failure)
            throw failure
        }
        return response.copy(bodyProvider = detached, bodyLease = detachedClaim)
    }

    private fun routeKey(path: String, methods: List<HttpMethod>) = "$path|${methods.joinToString(",")}"

    private fun nonEmpty(input: InputStream): InputStream? {
        val pushback = PushbackInputStream(input, 1)
        val first = try { pushback.read() } catch (f: Throwable) { closeAddingSuppressed(pushback, f); throw f }
        if (first == -1) { pushback.close(); return null }
        pushback.unread(first)
        return pushback
    }

    /**
     * The adapter's inbound claim ends with the request. Cleanup failures are logged and never
     * rewrite the response the request already produced.
     */
    private fun releaseInboundResources(requestResources: StreamingResourceRegistry) {
        try {
            requestResources.releaseAll()
        } catch (cleanupFailure: Throwable) {
            logger.error("Failed to release inbound request streaming resources", cleanupFailure)
        }
    }

    private fun closeLeaseAddingSuppressed(lease: StreamingResourceLease?, failure: Throwable) {
        try { lease?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
    }

    private fun endIfOpen(context: RoutingContext, statusCode: Int, body: String) {
        if (!context.response().ended()) {
            context.response().setStatusCode(statusCode).end(body)
        }
    }

    private fun newCoroutineScope(): CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun closeAddingSuppressed(input: InputStream, failure: Throwable) {
        try { input.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
    }

    private class BufferInputStream(private val buffer: Buffer) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos >= buffer.length()) -1 else buffer.getByte(pos++).toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= buffer.length()) return -1
            val toRead = minOf(len, buffer.length() - pos)
            buffer.getBytes(pos, pos + toRead, b, off)
            pos += toRead
            return toRead
        }
    }
}

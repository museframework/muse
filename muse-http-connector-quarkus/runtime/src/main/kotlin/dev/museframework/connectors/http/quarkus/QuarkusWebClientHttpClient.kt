package dev.museframework.connectors.http.quarkus

import dev.museframework.engine.api.streaming.StreamingProviders
import dev.museframework.engine.api.streaming.StreamingStrategy
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.api.processor.HttpOutboundRequest
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.streams.ReadStream
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.client.WebClientSession
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class QuarkusWebClientHttpClient(
    vertx: Vertx,
    private val config: HttpRequestConnectionConfig,
    private val streamingStrategy: StreamingStrategy = StreamingStrategy.RepeatableFileStore(),
) : HttpClient {
    private val closed = AtomicBoolean(false)
    private val client: io.vertx.ext.web.client.WebClient =
        if (config.enableCookies) {
            WebClientSession.create(WebClient.create(vertx, webClientOptions(config)))
        } else {
            WebClient.create(vertx, webClientOptions(config))
        }

    override suspend fun execute(request: HttpOutboundRequest): HttpResponse {
        check(!closed.get()) { "Quarkus HTTP client is closed" }
        val vertxRequest = client.requestAbs(
            mapMethod(request.method),
            request.url,
        )
        vertxRequest.followRedirects(request.followRedirects)
        (request.responseTimeoutMs ?: config.responseTimeoutMs)
            .takeIf { it > 0 }
            ?.let(vertxRequest::timeout)
        request.headers.forEach { (k, v) -> vertxRequest.putHeader(k, v) }
        if (request.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            request.contentType?.let { vertxRequest.putHeader("Content-Type", it) }
        }
        if (
            !config.usePersistentConnections &&
            request.headers.keys.none { it.equals("Connection", ignoreCase = true) }
        ) {
            vertxRequest.putHeader("Connection", "close")
        }
        val bodyProvider = request.bodyProvider
        val body = request.body
        val mode = request.requestStreamingMode
        val streamingBody = (mode == HttpStreamingMode.ALWAYS) && (bodyProvider != null || body != null)

        if (streamingBody) {
            vertxRequest.headers().remove("Content-Length")
        } else {
            val size =
                if (bodyProvider != null) bodyProvider.size.takeIf { it >= 0 }
                else body?.size?.toLong()
            if (size != null) vertxRequest.putHeader("Content-Length", size.toString())
        }

        request.queryParams.forEach { (k, v) -> vertxRequest.addQueryParam(k, v) }
        val vertxResponse =
            when {
                streamingBody && bodyProvider != null ->
                    awaitFuture(
                        vertxRequest.sendStream(
                            providerReadStream(bodyProvider),
                        ),
                    )
                streamingBody && body != null ->
                    awaitFuture(
                        vertxRequest.sendStream(
                            byteArrayReadStream(body),
                        ),
                    )
                bodyProvider != null -> {
                    val bytes = bodyProvider.openCursor().use { it.readBytes() }
                    awaitFuture(vertxRequest.sendBuffer(Buffer.buffer(bytes)))
                }
                body != null -> awaitFuture(vertxRequest.sendBuffer(Buffer.buffer(body)))
                else -> awaitFuture(vertxRequest.send())
            }
        return toHttpResponse(request.method, vertxResponse)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) client.close()
    }

    private suspend fun <T> awaitFuture(future: io.vertx.core.Future<T>): T =
        suspendCancellableCoroutine { cont ->
            future.onComplete { ar ->
                if (ar.succeeded()) cont.resume(ar.result())
                else cont.resumeWithException(ar.cause())
            }
        }

    private fun toHttpResponse(requestMethod: HttpMethod, response: io.vertx.ext.web.client.HttpResponse<Buffer>): HttpResponse {
        val statusCode = response.statusCode()
        val reasonPhrase = response.statusMessage()
        val headers = response.headers().entries().associate { it.key to it.value }
        if (!hasResponseBody(requestMethod, statusCode, response.headers().get("Content-Length")?.toLongOrNull() ?: -1)) {
            return HttpResponse(statusCode = statusCode, headers = headers, reasonPhrase = reasonPhrase)
        }
        val buffer = response.body()
        if (buffer == null || buffer.length() == 0) {
            return HttpResponse(statusCode = statusCode, headers = headers, reasonPhrase = reasonPhrase)
        }
        val input: InputStream = BufferInputStream(buffer)
        val nonEmpty = nonEmpty(input) ?: return HttpResponse(statusCode = statusCode, headers = headers, reasonPhrase = reasonPhrase)
        val defer = streamingStrategy is StreamingStrategy.NonRepeatable
        val provider = if (defer) {
            try {
                val managed = ResponseClosingInputStream(nonEmpty, response)
                StreamingProviders.byteProvider(managed, StreamingStrategy.NonRepeatable)
            } catch (failure: Exception) {
                nonEmpty.close()
                throw failure
            }
        } else {
            StreamingProviders.byteProvider(nonEmpty, streamingStrategy)
        }
        return HttpResponse(statusCode = statusCode, headers = headers, bodyProvider = provider, reasonPhrase = reasonPhrase)
    }

    private fun hasResponseBody(requestMethod: HttpMethod, statusCode: Int, contentLength: Long): Boolean =
        requestMethod != HttpMethod.HEAD && statusCode !in 100..199 && statusCode != 204 && statusCode != 304 && contentLength != 0L

    private fun nonEmpty(input: InputStream): InputStream? {
        val pushback = PushbackInputStream(input, 1)
        val first = pushback.read()
        if (first == -1) { pushback.close(); return null }
        pushback.unread(first)
        return pushback
    }

    private fun providerReadStream(provider: dev.museframework.engine.api.streaming.MuseCursorStreamProvider): ReadStream<Buffer> {
        val cursor = provider.openCursor()
        return QuarkusPullReadStream(
            read = { out -> cursor.read(out, 0, out.size) },
            onClose = cursor::close,
        )
    }

    private fun byteArrayReadStream(bytes: ByteArray): ReadStream<Buffer> {
        var offset = 0
        return QuarkusPullReadStream(
            read = { out ->
                if (offset >= bytes.size) {
                    -1
                } else {
                    val toRead = minOf(out.size, bytes.size - offset)
                    bytes.copyInto(out, 0, offset, offset + toRead)
                    offset += toRead
                    toRead
                }
            },
        )
    }

    private fun mapMethod(method: HttpMethod) = when (method) {
        HttpMethod.GET -> io.vertx.core.http.HttpMethod.GET
        HttpMethod.POST -> io.vertx.core.http.HttpMethod.POST
        HttpMethod.PUT -> io.vertx.core.http.HttpMethod.PUT
        HttpMethod.DELETE -> io.vertx.core.http.HttpMethod.DELETE
        HttpMethod.PATCH -> io.vertx.core.http.HttpMethod.PATCH
        HttpMethod.HEAD -> io.vertx.core.http.HttpMethod.HEAD
        HttpMethod.OPTIONS -> io.vertx.core.http.HttpMethod.OPTIONS
        HttpMethod.TRACE -> io.vertx.core.http.HttpMethod.TRACE
        HttpMethod.ANY -> io.vertx.core.http.HttpMethod.GET
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

    private class ResponseClosingInputStream(delegate: InputStream, private val response: io.vertx.ext.web.client.HttpResponse<*>) : FilterInputStream(delegate) {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var failure: Exception? = null
            try { super.close() } catch (e: Exception) { failure = e }
            failure?.let { throw it }
        }
    }

    companion object {
        internal fun webClientOptions(config: HttpRequestConnectionConfig): WebClientOptions =
            WebClientOptions()
                .setFollowRedirects(false)
                .setConnectTimeout(config.connectionTimeoutMs.toIntSafe())
                .setIdleTimeout(config.connectionIdleTimeoutMs.toVertxTimeout())
                .setIdleTimeoutUnit(TimeUnit.MILLISECONDS)
                .setKeepAlive(config.usePersistentConnections)
                .apply {
                    if (config.maxConnections > 0) setMaxPoolSize(config.maxConnections)
                }

        private fun Long.toIntSafe(): Int =
            coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

        private fun Long.toVertxTimeout(): Int =
            coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
}

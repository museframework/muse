package dev.museframework.http.api.model

import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingResourceLease
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.http.api.config.HttpStreamingMode
import java.util.Collections.emptyMap

/**
 * Adapter-neutral HTTP response.
 *
 * When [bodyProvider] is present, adapters stream it directly and ignore [body].
 *
 * [bodyLease] is the retained claim covering [bodyProvider]. A streaming response adapter closes
 * that lease after serialization, HEAD/no-body handling, disconnect or response-construction
 * failure instead of releasing the provider directly: the provider may be owned by the engine, the
 * reply source and the adapter at the same time. A client that returns a response built from a
 * provider it owns supplies the lease for it; the engine processor registers the provider in the
 * execution registry before closing that incoming lease. Adapter-constructed providers acquire
 * their own claim with [dev.museframework.engine.api.streaming.StreamingResourceRegistry.ownedClaim].
 */
data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val bodyProvider: MuseCursorStreamProvider? = null,
    val reasonPhrase: String? = null,
    val streamingMode: HttpStreamingMode = HttpStreamingMode.AUTO,
    val bodyLease: StreamingResourceLease? = null,
)

/**
 * Normalizes a provider-bearing response to exactly one owned lease.
 *
 * An adapter calls this as soon as the handler returns, before response-object, header or
 * content-length construction can fail: from that point the response owns its claim and every
 * later failure closes exactly that lease, so a locally built provider is never briefly unowned.
 */
fun HttpResponse.withOwnedBodyLease(): HttpResponse =
    if (bodyProvider != null && bodyLease == null) {
        copy(bodyLease = StreamingResourceRegistry.ownedClaim(bodyProvider))
    } else {
        this
    }

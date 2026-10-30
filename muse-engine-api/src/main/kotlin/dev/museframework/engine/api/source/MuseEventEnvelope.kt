package dev.museframework.engine.api.source

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.streaming.StreamingEventResources
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Carries one source event into the engine and its asynchronous reply back out.
 *
 * [executionResources] covers the event while it is queued and while it executes. The sending
 * source owns that registry until a successful channel send; the engine owns it afterwards and
 * releases it once request execution is finished. Channel undelivered-element handling and the
 * shutdown queue drain release events that were never executed.
 *
 * A source that exports streaming provider lifetime through its reply supplies [replyResources].
 * Before [replyTo] completes, the engine registers the final event in both this registry and the
 * still-open [replyResources]. The source builds its response while that claim is alive, registers
 * any response-expression-created providers, retains a lease for precisely the exported providers
 * and then closes [replyResources]; the response adapter closes that lease after serialization,
 * HEAD/no-body handling, disconnect or response-construction failure. A missing [replyResources]
 * means no provider lifetime is exported through this source.
 *
 * [requestCancellation] is completed by the source on timeout, abort or cancellation, never on
 * successful response delivery. Engine request execution watches it and cancels its request job.
 */
data class MuseEventEnvelope(
    val event: MuseEvent,
    val replyTo: CompletableDeferred<MuseEvent> = CompletableDeferred(),
    val transactionContext: Any? = null,
    val executionResources: StreamingResourceRegistry =
        StreamingResourceRegistry().also {
            StreamingEventResources.registerEventProviders(event, it)
        },
    val replyResources: StreamingResourceRegistry? = null,
    val requestCancellation: Deferred<Unit>? = null,
)

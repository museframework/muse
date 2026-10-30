package dev.museframework.engine.api.streaming

/**
 * Capability of a provider whose backing transport is shorter-lived than the resource lifetime the
 * engine needs, so that the provider can be made usable beyond the transport that produced it.
 *
 * The engine invokes preparation during provisional admission, before an accepted branch may consume
 * the provider after the originating request is gone. Ordinary providers that own a
 * lifetime-independent backing resource do not need this capability; transport adapters implement it
 * whenever provider ownership alone does not protect the underlying source.
 *
 * Preparation keeps the provider's identity and its cursor contract: it detaches the remaining input
 * into an independent backing through the configured spooling strategy and never turns a
 * non-repeatable provider into a replayable one. It therefore may delay acceptance by input-transfer
 * time and consume temporary storage, and it must be called before the first cursor is claimed.
 *
 * Failure contract:
 * - [IllegalStateException] when the backing input was already claimed by a cursor, or when a
 *   preparation is already in progress: independence can no longer be established, and the input is
 *   never silently rewound to hide it.
 * - [StreamingException] for an expected preparation failure, including a transport failure while
 *   detaching the input or a release that wins the race against preparation. Provisional resources are
 *   removed and the provider is left released: no cursor can be claimed from it afterwards.
 * - [kotlinx.coroutines.CancellationException] when the caller is cancelled, including while a read of
 *   the input is already blocked. The live input is closed so that the blocked read unwinds, provisional
 *   resources are removed, and the provider is left released. Cancellation is never reported as a
 *   preparation failure.
 */
interface BackgroundStreamPreparation {
    suspend fun prepareForBackgroundUse()
}

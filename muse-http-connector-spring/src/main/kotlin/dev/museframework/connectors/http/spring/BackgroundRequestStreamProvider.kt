package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.BackgroundStreamPreparation
import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.NonRepeatableCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingCleanupException
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.api.streaming.StreamingProviders
import dev.museframework.engine.api.streaming.StreamingResourceReleasedException
import dev.museframework.engine.api.streaming.StreamingStrategy
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Adapter-owned provider for an inbound request body whose backing transport is the servlet request.
 *
 * A [StreamingStrategy.NonRepeatable] request body is served as a live stream, so it can only be
 * consumed while that request is still connected. This wrapper keeps that live behaviour until
 * [prepareForBackgroundUse] detaches the remaining input into the configured repeatable spooling
 * strategy, after which the same wrapper serves the detached backing instead of the live transport.
 *
 * The wrapper owns exactly one public cursor for its whole life: a first-cursor claim and a
 * preparation are mutually exclusive through one atomic state transition, and neither promotion nor
 * claiming turns the provider into a repeatable one. A preparation that overlaps one already in
 * progress is rejected immediately instead of waiting for it. Cleanup is idempotent, holds no lock
 * while provider-supplied code runs, and closes the live transport first so a preparation blocked in
 * a read can always be unwound.
 */
class BackgroundRequestStreamProvider(
    inputStream: InputStream,
    private val spoolingStrategy: StreamingStrategy.RepeatableFileStore,
) : MuseCursorStreamProvider,
    BackgroundStreamPreparation {
    private val state = AtomicReference(State.LIVE_UNOPENED)
    private val liveInput = NonRepeatableCursorStreamProvider(inputStream)
    private val detachedBacking = AtomicReference<MuseCursorStreamProvider?>(null)
    private val resourcesReleased = AtomicBoolean(false)

    override val isClosed: Boolean
        get() = state.get() == State.RELEASED

    /** Unknown while the body is still the live transport, known once it was detached. */
    override val size: Long
        get() = detachedBacking.get()?.size ?: -1L

    override fun openCursor(): MuseCursorStream {
        while (true) {
            when (state.get()) {
                State.LIVE_UNOPENED ->
                    if (state.compareAndSet(State.LIVE_UNOPENED, State.CURSOR_OPENED)) {
                        return liveInput.openCursor()
                    }

                State.DETACHED_UNOPENED ->
                    if (state.compareAndSet(State.DETACHED_UNOPENED, State.CURSOR_OPENED)) {
                        return checkNotNull(detachedBacking.get()) { "The detached request body backing is missing" }.openCursor()
                    }

                State.PREPARING -> throw IllegalStateException(PREPARATION_IN_PROGRESS_MESSAGE)
                State.CURSOR_OPENED -> throw IllegalStateException(ALREADY_CLAIMED_MESSAGE)
                State.RELEASED -> throw StreamingResourceReleasedException()
            }
        }
    }

    override suspend fun prepareForBackgroundUse() {
        detachLiveInput()
    }

    /** This provider owns the request input, so closing it also releases the live transport. */
    override fun close() {
        releaseResourcesOnce()
    }

    override fun releaseResources() {
        releaseResourcesOnce()
    }

    private suspend fun detachLiveInput() {
        while (!state.compareAndSet(State.LIVE_UNOPENED, State.PREPARING)) {
            when (state.get()) {
                // Another preparation already detached this body; repeated preparation is a no-op.
                State.DETACHED_UNOPENED -> return
                State.PREPARING -> throw IllegalStateException(PREPARATION_IN_PROGRESS_MESSAGE)
                State.CURSOR_OPENED -> throw IllegalStateException(ALREADY_CLAIMED_MESSAGE)
                State.RELEASED -> throw StreamingResourceReleasedException()
                State.LIVE_UNOPENED -> Unit
            }
        }

        val closeOnCancellation = installCancellationHook()
        val detached =
            try {
                StreamingProviders.byteProvider(liveInput.openCursor(), spoolingStrategy)
            } catch (failure: Throwable) {
                val releasedConcurrently = state.get() == State.RELEASED
                discardFailedPreparation(detached = null, failure = failure)
                // A cancelled caller never sees a preparation failure: the exception it observes stays
                // cancellation, even though the copy itself fails on the closed transport.
                currentCoroutineContext().ensureActive()
                throw if (releasedConcurrently) StreamingResourceReleasedException() else preparationFailure(failure)
            } finally {
                closeOnCancellation?.dispose()
            }

        try {
            // A cancellation that lands while the copy finishes must not publish a backing the cancelled
            // caller cannot own; the freshly spooled backing is discarded with the failure.
            currentCoroutineContext().ensureActive()
            // Detached: drop the live transport before publishing the backing it was replaced by.
            liveInput.releaseResources()
            detachedBacking.set(detached)
            if (!state.compareAndSet(State.PREPARING, State.DETACHED_UNOPENED)) {
                throw StreamingResourceReleasedException()
            }
        } catch (failure: Throwable) {
            discardFailedPreparation(detached = detached, failure = failure)
            throw preparationFailure(failure)
        }
    }

    /**
     * A read that is already blocked inside [InputStream.read] cannot be unwound by a cancellation
     * check placed after the copy, so the hook closes the live transport as soon as cancellation is
     * requested. It runs on the thread that requests cancellation and is registered before the copy
     * starts; `invokeImmediately` also covers a caller that is cancelled before the copy begins.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun installCancellationHook(): DisposableHandle? {
        val job = currentCoroutineContext()[Job] ?: return null
        return job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            try {
                liveInput.releaseResources()
            } catch (ignored: Throwable) {
                // Best effort: this handler runs while the caller's job is being cancelled, and the
                // preparation path reports the outcome of the copy it is unwinding.
            }
        }
    }

    private fun discardFailedPreparation(
        detached: MuseCursorStreamProvider?,
        failure: Throwable,
    ) {
        try {
            detached?.releaseResources()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        try {
            releaseResourcesOnce()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    /**
     * A preparation that could not be completed leaves a partially consumed live transport, so the
     * provider can never serve a cursor again and is released instead.
     */
    private fun preparationFailure(failure: Throwable): Throwable =
        if (failure is CancellationException || failure is StreamingException) {
            failure
        } else {
            StreamingException("Failed to prepare the non-repeatable request body for background use", failure)
        }

    private fun releaseResourcesOnce() {
        if (!resourcesReleased.compareAndSet(false, true)) return
        state.set(State.RELEASED)
        val failures = mutableListOf<Throwable>()
        // The live transport is released first: a preparation blocked in a read of it can only be
        // unwound by closing that input. Provider cleanup runs outside the state transitions.
        try {
            liveInput.releaseResources()
        } catch (failure: Throwable) {
            failures += failure
        }
        detachedBacking.get()?.let { backing ->
            try {
                backing.releaseResources()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        if (failures.isNotEmpty()) throw StreamingCleanupException(failures)
    }

    private enum class State {
        /** The live request input is available and no public cursor was claimed. */
        LIVE_UNOPENED,

        /** The live input is being copied into the configured spooling strategy. */
        PREPARING,

        /** The body is detached and no public cursor was claimed. */
        DETACHED_UNOPENED,

        /** The single public cursor was claimed. */
        CURSOR_OPENED,

        /** Resources were released, or release began. */
        RELEASED,
    }

    private companion object {
        const val PREPARATION_IN_PROGRESS_MESSAGE =
            "The non-repeatable request body is being prepared for background use; no cursor can be claimed meanwhile"

        const val ALREADY_CLAIMED_MESSAGE =
            "The non-repeatable request body stream has already been claimed by a cursor"
    }
}

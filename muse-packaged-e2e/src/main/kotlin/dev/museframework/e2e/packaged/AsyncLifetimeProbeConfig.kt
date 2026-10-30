package dev.museframework.e2e.packaged

import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.async
import dev.museframework.dsl.dsl.customCode
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.streaming.MuseCursor
import dev.museframework.engine.api.streaming.MuseCursorProvider
import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.api.streaming.StreamingResourceReleasedException
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.springboot.MuseConfigProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Packaged fixture for the async lifetime contract.
 *
 * The endpoints are synthetic test infrastructure: they expose fixture-local counters, gates and a
 * synthetic read result, never an engine diagnostic or observer API. `start` accepts one gated
 * background branch and answers immediately; `release` opens the gate and waits for the bounded
 * branch-completion signal; `state` reports the fixture counters; `failing-reply` answers with a
 * provider whose real response serialization fails after a fixed prefix.
 */
@Component
class AsyncLifetimeProbeConfig : MuseConfigProvider {
    override fun flows(): List<MuseFlow> =
        listOf(
            flow("policy-async-lifetime-start") {
                httpListener("packaged-listener") {
                    path = "/policy/async/start"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                resetProbe(AsyncLifetimeProbe.START)
                async {
                    maxConcurrency = 4
                    gatedBranch()
                }
                setVariable("httpStatus") {
                    value = "202"
                }
                setPayload {
                    value = "accepted"
                }
            },
            flow("policy-async-lifetime-release") {
                httpListener("packaged-listener") {
                    path = "/policy/async/release"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                customCode {
                    docName = "Release the gated async branch and report its result"
                    handleErrors = false
                    code { _ -> AsyncLifetimeProbe.releaseAndAwaitCompletion() }
                }
            },
            flow("policy-async-lifetime-state") {
                httpListener("packaged-listener") {
                    path = "/policy/async/state"
                    allowedMethods = listOf(HttpMethod.GET)
                }
                customCode {
                    docName = "Report the async lifetime probe counters"
                    handleErrors = false
                    code { _ -> AsyncLifetimeProbe.snapshot() }
                }
            },
            flow("policy-async-lifetime-failing-reply") {
                httpListener("packaged-listener") {
                    path = "/policy/async/failing-reply"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                resetProbe(AsyncLifetimeProbe.FAILING_REPLY)
                async {
                    maxConcurrency = 4
                    gatedBranch()
                }
                customCode {
                    docName = "Synthetic self-failing reply body"
                    handleErrors = false
                    code { _ -> SelfFailingReplyProvider() }
                }
            },
            flow("policy-async-lifetime-disconnected-reply") {
                httpListener("packaged-listener") {
                    path = "/policy/async/disconnected-reply"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                resetProbe(AsyncLifetimeProbe.DISCONNECTED_REPLY)
                async {
                    maxConcurrency = 4
                    gatedBranch()
                }
                customCode {
                    docName = "Gated synthetic reply body that never fails on its own"
                    handleErrors = false
                    code { _ -> GatedReplyProvider() }
                }
            },
            flow("policy-async-lifetime-release-reply") {
                httpListener("packaged-listener") {
                    path = "/policy/async/release-reply"
                    allowedMethods = listOf(HttpMethod.POST)
                }
                customCode {
                    docName = "Open the gated reply write and report the probe state"
                    handleErrors = false
                    code { _ -> AsyncLifetimeProbe.releaseReplyWriteGate() }
                }
            },
        )
}

private fun PipelineOwner.resetProbe(scenario: String) {
    customCode {
        docName = "Reset the async lifetime probe"
        handleErrors = false
        code { payload ->
            AsyncLifetimeProbe.reset(scenario)
            payload
        }
    }
}

private fun PipelineOwner.gatedBranch() {
    customCode {
        docName = "Gated async branch reading its retained input"
        handleErrors = false
        code { payload ->
            val provider = payload as? MuseCursorStreamProvider
                ?: error("The async branch requires a retained streaming payload")
            val read = AsyncLifetimeProbe.awaitReleaseAndRead(provider)
            if (read == null) {
                "branch-gate-timeout"
            } else {
                payload
            }
        }
    }
}

/**
 * Fixture-local state for the packaged async lifetime sequence. All gates, counters and the read
 * result belong to the fixture; nothing here is a runtime observer or a durable job record.
 */
object AsyncLifetimeProbe {
    const val START = "start"
    const val FAILING_REPLY = "failing-reply"
    const val DISCONNECTED_REPLY = "disconnected-reply"

    /** Bytes the self-failing reply streams before its own deterministic failure. */
    const val SERIALIZED_PREFIX_BYTES = 8 * 1024 * 1024

    /**
     * Bytes the gated reply declares. It never fails on its own, so a reply that does not serve all
     * of them can only have been interrupted by the transport.
     */
    const val DISCONNECT_REPLY_BYTES = 32 * 1024 * 1024

    private const val BRANCH_GATE_TIMEOUT_MILLIS = 30_000L
    private const val BRANCH_COMPLETION_TIMEOUT_MILLIS = 15_000L
    private const val REPLY_WRITE_GATE_TIMEOUT_MILLIS = 30_000L
    private const val REPLY_WRITE_ACK_MILLIS = 5_000L
    private const val REPLY_WRITE_ACK_POLL_MILLIS = 5L

    private val scenario = AtomicReference("")
    private val branchEntered = AtomicBoolean(false)
    private val branchCompleted = AtomicBoolean(false)
    private val read = AtomicReference<String?>(null)
    private val releaseGate = AtomicReference(CompletableDeferred<Unit>())
    private val branchCompletion = AtomicReference(CompletableDeferred<Unit>())
    private val replyWriteGate = AtomicReference(CountDownLatch(1))
    private val replyWriteEntered = AtomicBoolean(false)
    private val replyWriteReleased = AtomicBoolean(false)
    private val replyWriteTimedOut = AtomicBoolean(false)
    private val replyCompleted = AtomicBoolean(false)
    private val replyCloses = AtomicInteger()
    private val replyReleases = AtomicInteger()
    private val replyServedBytes = AtomicLong()
    private val replyReadFailures = AtomicInteger()

    fun reset(scenarioName: String) {
        scenario.set(scenarioName)
        branchEntered.set(false)
        branchCompleted.set(false)
        read.set(null)
        releaseGate.set(CompletableDeferred())
        branchCompletion.set(CompletableDeferred())
        replyWriteGate.set(CountDownLatch(1))
        replyWriteEntered.set(false)
        replyWriteReleased.set(false)
        replyWriteTimedOut.set(false)
        replyCompleted.set(false)
        replyCloses.set(0)
        replyReleases.set(0)
        replyServedBytes.set(0)
        replyReadFailures.set(0)
    }

    /** Suspends inside the branch until `release` opens the gate; returns the read text, or null on timeout. */
    suspend fun awaitReleaseAndRead(provider: MuseCursorStreamProvider): String? {
        branchEntered.set(true)
        val admitted =
            withTimeoutOrNull(BRANCH_GATE_TIMEOUT_MILLIS) {
                releaseGate.get().await()
            } != null
        if (!admitted) {
            branchCompletion.get().complete(Unit)
            return null
        }
        val text = provider.openCursor().use { cursor -> String(cursor.readBytes(), StandardCharsets.UTF_8) }
        read.set(text)
        branchCompleted.set(true)
        branchCompletion.get().complete(Unit)
        return text
    }

    suspend fun releaseAndAwaitCompletion(): String {
        releaseGate.get().complete(Unit)
        withTimeoutOrNull(BRANCH_COMPLETION_TIMEOUT_MILLIS) { branchCompletion.get().await() }
        return snapshot()
    }

    /**
     * Holds the first response-body read until `release-reply` opens the gate: the adapter is then
     * inside a real response write with no byte of the body written yet.
     */
    fun awaitReplyWriteGate() {
        replyWriteEntered.set(true)
        val opened = replyWriteGate.get().await(REPLY_WRITE_GATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        if (opened) {
            replyWriteReleased.set(true)
        } else {
            replyWriteTimedOut.set(true)
        }
    }

    fun releaseReplyWriteGate(): String {
        replyWriteGate.get().countDown()
        // Bounded acknowledgement: the servlet thread must have observed the open gate before the
        // snapshot is taken, otherwise the caller could read a pre-release state.
        val deadline = System.nanoTime() + REPLY_WRITE_ACK_MILLIS * 1_000_000
        while (!replyWriteReleased.get() && System.nanoTime() < deadline) {
            Thread.sleep(REPLY_WRITE_ACK_POLL_MILLIS)
        }
        return snapshot()
    }

    fun snapshot(): String =
        "scenario=${scenario.get()}" +
            ";branchEntered=${branchEntered.get()}" +
            ";branchCompleted=${branchCompleted.get()}" +
            ";read=${read.get() ?: "none"}" +
            ";replyWriteEntered=${replyWriteEntered.get()}" +
            ";replyWriteReleased=${replyWriteReleased.get()}" +
            ";replyWriteTimedOut=${replyWriteTimedOut.get()}" +
            ";replyCompleted=${replyCompleted.get()}" +
            ";replyCloses=${replyCloses.get()}" +
            ";replyReleases=${replyReleases.get()}" +
            ";replyServedBytes=${replyServedBytes.get()}" +
            ";replyReadFailures=${replyReadFailures.get()}"

    fun markReplyClose() {
        replyCloses.incrementAndGet()
    }

    fun markReplyRelease() {
        replyReleases.incrementAndGet()
    }

    fun addReplyServedBytes(count: Int) {
        replyServedBytes.addAndGet(count.toLong())
    }

    fun markReplyReadFailure() {
        replyReadFailures.incrementAndGet()
    }

    fun markReplyCompleted() {
        replyCompleted.set(true)
    }
}

/** Counts exactly one last-owner cleanup, the way an engine registry/lease pair would perform it. */
private abstract class SyntheticReplyProviderBase(
    private val declaredSize: Long,
) : MuseCursorStreamProvider {
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    override val isClosed: Boolean
        get() = closed.get()

    override val size: Long
        get() = declaredSize

    override fun openCursor(): MuseCursorStream {
        if (released.get()) throw StreamingResourceReleasedException()
        if (closed.get()) throw StreamingException("The synthetic reply provider is closed")
        return cursor()
    }

    protected abstract fun cursor(): MuseCursorStream

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            AsyncLifetimeProbe.markReplyClose()
        }
    }

    override fun releaseResources() {
        if (released.compareAndSet(false, true)) {
            AsyncLifetimeProbe.markReplyRelease()
        }
        closed.set(true)
    }
}

private abstract class SyntheticReplyCursor(
    private val totalBytes: Long,
    override val provider: MuseCursorProvider<out MuseCursor>,
) : MuseCursorStream() {
    private var index = 0
    private var released = false

    override val position: Long
        get() = index.toLong()

    override val isReleased: Boolean
        get() = released

    override fun seek(position: Long) {
        index = position.toInt()
    }

    override fun release() {
        released = true
    }

    override fun close() {
        release()
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val count = read(single, 0, 1)
        return if (count < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (released) throw StreamingResourceReleasedException()
        val remaining = totalBytes - index
        if (remaining <= 0) return -1
        onRead()
        val count = minOf(length.toLong(), remaining).toInt()
        Arrays.fill(buffer, offset, offset + count, 'r'.code.toByte())
        index += count
        AsyncLifetimeProbe.addReplyServedBytes(count)
        return count
    }

    /** Runs before each served chunk; the gated cursor uses it as a one-shot gate. */
    protected abstract fun onRead()
}

/**
 * Self-failing response body: streams [AsyncLifetimeProbe.SERIALIZED_PREFIX_BYTES] and then throws.
 * This is the deterministic serialization-failure proof; it is not a transport-failure proof.
 */
private class SelfFailingReplyProvider :
    SyntheticReplyProviderBase(AsyncLifetimeProbe.SERIALIZED_PREFIX_BYTES.toLong()) {
    override fun cursor(): MuseCursorStream = SelfFailingReplyCursor(this)
}

private class SelfFailingReplyCursor(
    provider: MuseCursorStreamProvider,
) : SyntheticReplyCursor(AsyncLifetimeProbe.SERIALIZED_PREFIX_BYTES.toLong(), provider) {
    override fun onRead() = Unit

    /** Streaming the whole declared prefix is the failure for this provider, never a completion. */
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val served = super.read(buffer, offset, length)
        if (served < 0) {
            AsyncLifetimeProbe.markReplyReadFailure()
            throw StreamingException("Synthetic response serialization failure after the declared prefix")
        }
        return served
    }
}

/**
 * Gated response body that never fails on its own: its first read waits for the fixture gate, so a
 * test can reset the client connection while the adapter is poised inside the response write.
 */
private class GatedReplyProvider :
    SyntheticReplyProviderBase(AsyncLifetimeProbe.DISCONNECT_REPLY_BYTES.toLong()) {
    override fun cursor(): MuseCursorStream = GatedReplyCursor(this)
}

private class GatedReplyCursor(
    provider: MuseCursorStreamProvider,
) : SyntheticReplyCursor(AsyncLifetimeProbe.DISCONNECT_REPLY_BYTES.toLong(), provider) {
    private val gate = AtomicBoolean(false)

    override fun onRead() {
        if (gate.compareAndSet(false, true)) {
            AsyncLifetimeProbe.awaitReplyWriteGate()
        }
    }

    /** This provider never fails on its own, so serving the declared body means the write completed. */
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val served = super.read(buffer, offset, length)
        if (served < 0) AsyncLifetimeProbe.markReplyCompleted()
        return served
    }
}

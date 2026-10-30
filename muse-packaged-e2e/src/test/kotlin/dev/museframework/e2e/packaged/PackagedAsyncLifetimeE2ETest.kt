package dev.museframework.e2e.packaged

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Packaged process/transport proof for the async lifetime contracts (S14/S21/S24).
 *
 * The application runs with the `non-repeatable` inbound strategy, so the listener input is a live
 * request-bound stream that can only outlive the request through the adapter's background
 * preparation. The fixture counters are synthetic and fixture-local.
 *
 * Two reply-side proofs are deliberately separate: the self-failing reply proves deterministic
 * response-serialization-failure cleanup, and the gated reply proves transport-failure cleanup while
 * the client connection is reset with the adapter poised inside the response write.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackagedAsyncLifetimeE2ETest : PackagedApplicationProcessSupport() {
    override val instanceId: String = "async-lifetime"

    override fun applicationArguments(tempDirectory: Path): List<String> =
        listOf(
            "--muse.streaming.strategy=non-repeatable",
            "--muse.streaming.file-store.in-memory-size-bytes=4",
            "--muse.streaming.file-store.temp-directory=$tempDirectory",
            "--muse.streaming.file-store.delete-files-on-close=true",
        )

    @Test
    fun `packaged main reply returns before the accepted branch reads its retained non-repeatable input`() {
        try {
            val start = postText("/policy/async/start", "synthetic-late-input", Duration.ofSeconds(10))

            assertEquals(202, start.statusCode())
            assertEquals("accepted", start.body().decodeToString())

            val blocked =
                awaitProbeState("the accepted branch is entered and still blocked") { state ->
                    state.startsWith("scenario=start;branchEntered=true;branchCompleted=false;read=none")
                }
            assertTrue(
                streamingTempFiles().isNotEmpty(),
                "The non-repeatable request body must be promoted before acceptance: $blocked",
            )

            val release = post("/policy/async/release", ByteArray(0), Duration.ofSeconds(20))

            assertEquals(200, release.statusCode())
            assertEquals(
                "scenario=start;branchEntered=true;branchCompleted=true;read=synthetic-late-input" +
                    ";replyWriteEntered=false;replyWriteReleased=false;replyWriteTimedOut=false;replyCompleted=false" +
                    ";replyCloses=0;replyReleases=0;replyServedBytes=0;replyReadFailures=0",
                release.body().decodeToString(),
            )

            awaitStreamingCleanup()
            assertEquals(
                emptyList(),
                streamingTempFiles(),
                "The promoted request body must release its spool file once the branch is done",
            )
        } finally {
            // A gate is never left closed on a failing assertion: the branch is released, and the
            // application is stopped with a final close either way.
            releaseBranchQuietly()
        }
    }

    @Test
    fun `packaged response serialization failure closes the reply lease and leaves the accepted branch readable`() {
        // The client-side outcome is deliberately not asserted: the reply declares its full length, so
        // a client can receive a complete body before the fixture failure is reached on the next read.
        // The fixture counters are the oracle for the failure and for the cleanup it must trigger.
        runCatching { postText("/policy/async/failing-reply", "synthetic-serialization-input", Duration.ofSeconds(10)) }

        try {
            val failed =
                awaitProbeState("the reply failed during serialization and its lease was closed exactly once") { state ->
                    field(state, "replyReadFailures") == "1" && field(state, "replyReleases") == "1"
                }

            assertEquals("1", field(failed, "replyCloses"), failed)
            assertEquals(
                AsyncLifetimeProbe.SERIALIZED_PREFIX_BYTES.toString(),
                field(failed, "replyServedBytes"),
                failed,
            )
            assertEquals("false", field(failed, "replyCompleted"), failed)
            assertEquals("false", field(failed, "branchCompleted"), failed)
            assertEquals("none", field(failed, "read"), failed)

            val release = post("/policy/async/release", ByteArray(0), Duration.ofSeconds(20))

            assertEquals(200, release.statusCode())
            val released = release.body().decodeToString()
            assertEquals("true", field(released, "branchCompleted"), released)
            assertEquals("synthetic-serialization-input", field(released, "read"), released)

            awaitStreamingCleanup()
            assertEquals(
                emptyList(),
                streamingTempFiles(),
                "The promoted request body must release its spool file once the branch is done",
            )
        } finally {
            releaseBranchQuietly()
        }
    }

    @Test
    fun `packaged transport failure while the reply is written closes the reply lease and leaves the accepted branch readable`() {
        val payload = "synthetic-disconnect-input"
        val socket = Socket()
        try {
            socket.setSoLinger(true, 0)
            socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS)
            writeRequest(socket, "/policy/async/disconnected-reply", payload.toByteArray())
            awaitProbeState("the request input was accepted before the transport is reset") { state ->
                field(state, "branchEntered") == "true"
            }

            // The adapter is inside the response write and no body byte reached the transport yet.
            awaitProbeState("the reply write is poised on its first body read") { state ->
                field(state, "replyWriteEntered") == "true"
            }

            socket.close() // SO_LINGER(0) resets the connection while the write is in progress

            val poised =
                awaitProbeState("the reset connection is established and the reply is still poised") { state ->
                    field(state, "replyWriteEntered") == "true" && field(state, "replyServedBytes") == "0"
                }
            assertEquals("false", field(poised, "replyCompleted"), poised)

            val released = post("/policy/async/release-reply", ByteArray(0), Duration.ofSeconds(20))

            assertEquals(200, released.statusCode())
            assertEquals("true", field(released.body().decodeToString(), "replyWriteReleased"))

            val failed =
                awaitProbeState("the interrupted reply write closed its lease exactly once") { state ->
                    field(state, "replyReleases") == "1"
                }

            assertEquals("1", field(failed, "replyCloses"), failed)
            assertEquals(
                "0",
                field(failed, "replyReadFailures"),
                "The reply provider never fails on its own, so the failure can only come from the transport: $failed",
            )
            assertEquals("false", field(failed, "replyWriteTimedOut"), failed)
            assertEquals("false", field(failed, "replyCompleted"), failed)
            assertTrue(
                field(failed, "replyServedBytes").toLong() < AsyncLifetimeProbe.DISCONNECT_REPLY_BYTES,
                "The reply write must not complete on a reset transport: $failed",
            )
            assertEquals("false", field(failed, "branchCompleted"), failed)
            assertEquals("none", field(failed, "read"), failed)

            val release = post("/policy/async/release", ByteArray(0), Duration.ofSeconds(20))

            assertEquals(200, release.statusCode())
            val resumed = release.body().decodeToString()
            assertEquals("true", field(resumed, "branchCompleted"), resumed)
            assertEquals(payload, field(resumed, "read"), resumed)

            awaitStreamingCleanup()
            assertEquals(
                emptyList(),
                streamingTempFiles(),
                "The promoted request body must release its spool file once the branch is done",
            )
        } finally {
            runCatching { socket.close() }
            releaseBranchQuietly()
            runCatching { post("/policy/async/release-reply", ByteArray(0), Duration.ofSeconds(5)) }
        }
    }

    private fun releaseBranchQuietly() {
        runCatching { post("/policy/async/release", ByteArray(0), Duration.ofSeconds(20)) }
    }

    private fun writeRequest(
        socket: Socket,
        path: String,
        body: ByteArray,
    ) {
        val head =
            "POST $path HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun awaitProbeState(
        expectation: String,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = System.nanoTime() + PROBE_TIMEOUT.toNanos()
        var lastObservation = "no fixture state was observed"
        while (System.nanoTime() < deadline) {
            lastObservation = probeState()
            if (predicate(lastObservation)) return lastObservation
            Thread.sleep(PROBE_RETRY_MILLIS)
        }
        error("The fixture never reported that $expectation; last state: $lastObservation")
    }

    private fun probeState(): String {
        val response = get("/policy/async/state", Duration.ofSeconds(5))
        assertEquals(200, response.statusCode())
        return response.body().decodeToString()
    }

    private fun field(
        state: String,
        name: String,
    ): String = state.substringAfter("$name=").substringBefore(';')

    private companion object {
        val PROBE_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val PROBE_RETRY_MILLIS = 25L
        const val CONNECT_TIMEOUT_MILLIS = 2_000
    }
}

package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.api.streaming.StreamingResourceReleasedException
import dev.museframework.engine.api.streaming.StreamingStrategy
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundRequestStreamProviderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `background promotion preserves one cursor and independent backing`() = runTest {
        val input = ByteArrayInputStream("after-reply".toByteArray())
        val provider = BackgroundRequestStreamProvider(input, spoolingStrategy())

        provider.prepareForBackgroundUse()
        input.close()

        assertEquals("after-reply", provider.openCursor().use { String(it.readBytes()) })
        assertEquals(11L, provider.size)
        assertFailsWith<IllegalStateException> { provider.openCursor() }

        provider.releaseResources()
        assertEquals(emptyList<Path>(), spoolingFiles())
    }

    @Test
    fun `promotion keeps the body readable after the live transport was closed`() = runTest {
        val input = CloseSensitiveInputStream("disconnect-payload")
        val provider = BackgroundRequestStreamProvider(input, spoolingStrategy())

        provider.prepareForBackgroundUse()

        assertTrue(input.isClosed, "promotion must close the live transport it detached from")
        assertContentEquals("disconnect-payload".toByteArray(), provider.openCursor().use { it.readBytes() })
        assertFalse(input.readAfterClose, "a detached read must never touch the live transport")

        provider.releaseResources()
        assertEquals(emptyList<Path>(), spoolingFiles())
    }

    @Test
    fun `promotion honours a memory resident threshold without temporary files`() = runTest {
        val provider =
            BackgroundRequestStreamProvider(
                CloseSensitiveInputStream("memory-resident"),
                spoolingStrategy(inMemorySizeBytes = 1024),
            )

        provider.prepareForBackgroundUse()

        assertEquals(emptyList<Path>(), spoolingFiles())
        assertEquals("memory-resident", provider.openCursor().use { String(it.readBytes()) })

        provider.releaseResources()
        assertEquals(emptyList<Path>(), spoolingFiles())
    }

    @Test
    fun `a promoted backing honours configured retention until it is released`() = runTest {
        val provider =
            BackgroundRequestStreamProvider(
                CloseSensitiveInputStream("retained-backing"),
                spoolingStrategy(inMemorySizeBytes = 4, deleteFilesOnClose = false),
            )

        provider.prepareForBackgroundUse()

        assertEquals(1, spoolingFiles().size, "the spooled backing must spill into the configured directory")
        assertEquals("retained-backing", provider.openCursor().use { String(it.readBytes()) })

        provider.releaseResources()

        assertEquals(1, spoolingFiles().size, "a configured retained backing must survive release")
        spoolingFiles().forEach { Files.deleteIfExists(it) }
        assertEquals(emptyList<Path>(), spoolingFiles())
    }

    @Test
    fun `a claimed input is rejected instead of silently rewound`() = runTest {
        val input = CloseSensitiveInputStream("live-payload")
        val provider = BackgroundRequestStreamProvider(input, spoolingStrategy())

        val cursor = provider.openCursor()
        assertEquals('l', cursor.read().toChar())

        assertFailsWith<IllegalStateException> { provider.prepareForBackgroundUse() }

        assertEquals("ive-payload", cursor.use { String(it.readBytes()) })
        assertFailsWith<IllegalStateException> { provider.openCursor() }
        assertFalse(provider.isClosed, "a rejected admission must leave the claimed live cursor usable")
        assertFalse(input.isClosed)
        assertEquals(emptyList<Path>(), spoolingFiles())

        provider.releaseResources()
        assertTrue(input.isClosed)
    }

    @Test
    fun `repeated preparation is idempotent and copies the live input once`() = runTest {
        val input = CloseSensitiveInputStream("copy-once")
        val provider = BackgroundRequestStreamProvider(input, spoolingStrategy())
        provider.prepareForBackgroundUse()
        val readCallsAfterPromotion = input.readCalls

        provider.prepareForBackgroundUse()

        assertEquals(readCallsAfterPromotion, input.readCalls, "a repeated preparation must not read the live input again")
        assertEquals("copy-once", provider.openCursor().use { String(it.readBytes()) })

        provider.releaseResources()
        assertEquals(emptyList<Path>(), spoolingFiles())
    }

    @Test
    fun `a preparation that overlaps one in progress is rejected immediately`() {
        runBlocking {
            val input = BlockingSuffixInputStream("overlapping-preparation")
            val provider = BackgroundRequestStreamProvider(input, spoolingStrategy(inMemorySizeBytes = 4))
            val scope = CoroutineScope(Dispatchers.IO)
            val first = CompletableDeferred<Throwable?>()
            try {
                scope.launch { first.complete(preparationOutcome(provider)) }
                assertTrue(input.awaitReadBlocked(5, TimeUnit.SECONDS), "the first preparation must block in the live read")

                val overlapping = withTimeoutOrNull(5_000) { preparationOutcome(provider) }

                assertIs<IllegalStateException>(overlapping)
                assertEquals(1, spoolingFiles().size, "the rejected preparation must not touch the live input")

                input.completeRead()
                assertTrue(settled(first), "the first preparation must still complete")
                assertNull(first.await())
                assertEquals("overlapping-preparation", provider.openCursor().use { String(it.readBytes()) })
                assertFailsWith<IllegalStateException> { provider.openCursor() }
            } finally {
                scope.cancel()
                provider.releaseResources()
            }
            assertEquals(emptyList<Path>(), spoolingFiles())
        }
    }

    @Test
    fun `only one of two concurrent claims is served and the other is refused`() {
        runBlocking {
            val provider = BackgroundRequestStreamProvider(CloseSensitiveInputStream("concurrent-claims"), spoolingStrategy())
            provider.prepareForBackgroundUse()
            val scope = CoroutineScope(Dispatchers.IO)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            try {
                val claims =
                    (1..2).map {
                        scope.async {
                            ready.countDown()
                            start.await(5, TimeUnit.SECONDS)
                            claimOutcome(provider)
                        }
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS), "both claims must be ready before the race starts")
                start.countDown()

                val outcomes = claims.map { it.await() }
                val served = outcomes.map { it.first }.filterNotNull()
                val refused = outcomes.map { it.second }.filterNotNull()

                assertEquals(1, served.size, "exactly one concurrent claim may be served")
                assertEquals(1, refused.size, "exactly one concurrent claim must be refused")
                assertIs<IllegalStateException>(refused.single())
                assertEquals("concurrent-claims", served.single().use { String(it.readBytes()) })
            } finally {
                scope.cancel()
                provider.releaseResources()
            }
            assertEquals(emptyList<Path>(), spoolingFiles())
        }
    }

    @Test
    fun `closing the provider while a preparation is blocked unwinds the blocked read`() {
        runBlocking {
            val input = BlockingSuffixInputStream("close-during-preparation")
            val provider = BackgroundRequestStreamProvider(input, spoolingStrategy(inMemorySizeBytes = 4))
            val scope = CoroutineScope(Dispatchers.IO)
            val outcome = CompletableDeferred<Throwable?>()
            try {
                scope.launch { outcome.complete(preparationOutcome(provider)) }
                assertTrue(input.awaitReadBlocked(5, TimeUnit.SECONDS), "the preparation must block in the live read")
                assertEquals(1, spoolingFiles().size, "the provisional spool file must exist while the copy is blocked")

                provider.close()

                assertTrue(settled(outcome), "closing the provider must unwind the blocked read")
                assertIs<StreamingResourceReleasedException>(outcome.await())
                assertEquals(emptyList<Path>(), spoolingFiles())
                assertTrue(input.isClosed)
                assertTrue(provider.isClosed)
                assertFailsWith<StreamingResourceReleasedException> { provider.openCursor() }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `cancelling a blocked preparation closes the live read and releases the provider`() = runBlocking {
        assertCancelledBlockedPreparation(deleteFilesOnClose = true)
    }

    @Test
    fun `a cancelled preparation drops its provisional file even when the configured backing is retained`() = runBlocking {
        assertCancelledBlockedPreparation(deleteFilesOnClose = false)
    }

    @Test
    fun `a cancellation that lands as the copy finishes leaves no detached backing behind`() {
        runBlocking {
            val job = Job()
            val input = CancellingAtEndInputStream("late-cancellation", job::cancel)
            val provider = BackgroundRequestStreamProvider(input, spoolingStrategy(inMemorySizeBytes = 4))
            val scope = CoroutineScope(Dispatchers.IO + job)
            val outcome = CompletableDeferred<Throwable?>()
            try {
                scope.launch { outcome.complete(preparationOutcome(provider)) }

                assertIs<CancellationException>(outcome.await())
                assertTrue(input.isClosed)
                assertEquals(emptyList<Path>(), spoolingFiles())
                assertTrue(provider.isClosed)
                assertFailsWith<StreamingResourceReleasedException> { provider.openCursor() }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `an input failure during preparation drops the provisional file and releases the provider`() = runTest {
        val input = FailingInputStream("failing-payload", IOException("connection reset by the client"))
        val provider = BackgroundRequestStreamProvider(input, spoolingStrategy(inMemorySizeBytes = 4))

        val failure = assertFailsWith<StreamingException> { provider.prepareForBackgroundUse() }

        assertIs<IOException>(failure.cause)
        assertEquals(emptyList<Path>(), spoolingFiles())
        assertTrue(provider.isClosed)
        assertFailsWith<StreamingResourceReleasedException> { provider.openCursor() }
        assertFailsWith<StreamingResourceReleasedException> { provider.prepareForBackgroundUse() }
    }

    @Test
    fun `promotion does not make the provider replayable`() = runTest {
        val provider = BackgroundRequestStreamProvider(CloseSensitiveInputStream("one-shot"), spoolingStrategy())
        provider.prepareForBackgroundUse()

        val cursor = provider.openCursor()
        assertEquals("one-shot", String(cursor.readBytes()))
        cursor.close()

        assertFailsWith<IllegalStateException> { provider.openCursor() }

        provider.releaseResources()
        assertEquals(emptyList<Path>(), spoolingFiles())
    }

    @Test
    fun `release is idempotent and rejects any later preparation`() = runTest {
        val input = CloseSensitiveInputStream("released")
        val provider = BackgroundRequestStreamProvider(input, spoolingStrategy())

        provider.releaseResources()
        provider.releaseResources()
        provider.close()

        assertTrue(provider.isClosed)
        assertTrue(input.isClosed)
        assertFailsWith<StreamingResourceReleasedException> { provider.prepareForBackgroundUse() }
        assertFailsWith<StreamingResourceReleasedException> { provider.openCursor() }
        assertEquals(emptyList<Path>(), spoolingFiles())
    }

    /**
     * The fake blocks until the wrapper closes it, so cancellation can only complete while that read is
     * still blocked if the wrapper closes the live transport when cancellation is requested. The test
     * never unblocks or closes the fake itself.
     */
    private suspend fun assertCancelledBlockedPreparation(deleteFilesOnClose: Boolean) {
        val input = BlockingSuffixInputStream("cancel-during-preparation")
        val provider =
            BackgroundRequestStreamProvider(
                input,
                spoolingStrategy(inMemorySizeBytes = 4, deleteFilesOnClose = deleteFilesOnClose),
            )
        val scope = CoroutineScope(Dispatchers.IO)
        val outcome = CompletableDeferred<Throwable?>()
        try {
            val job = scope.launch { outcome.complete(preparationOutcome(provider)) }
            assertTrue(input.awaitReadBlocked(5, TimeUnit.SECONDS), "the preparation must block in the live read")
            assertEquals(1, spoolingFiles().size, "the provisional spool file must exist while the copy is blocked")

            val joined = withTimeoutOrNull(5_000) { job.cancelAndJoin(); true }

            assertTrue(joined == true, "a cancelled preparation must unwind its blocked read without the test unblocking it")
            assertIs<CancellationException>(outcome.await())
            assertTrue(input.isClosed, "the cancellation hook must close the live input")
            assertEquals(emptyList<Path>(), spoolingFiles())
            assertTrue(provider.isClosed)
            assertFailsWith<StreamingResourceReleasedException> { provider.openCursor() }
            assertFailsWith<StreamingResourceReleasedException> { provider.prepareForBackgroundUse() }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun claimOutcome(provider: BackgroundRequestStreamProvider): Pair<MuseCursorStream?, Throwable?> =
        try {
            provider.openCursor() to null
        } catch (failure: Throwable) {
            null to failure
        }

    private suspend fun preparationOutcome(provider: BackgroundRequestStreamProvider): Throwable? =
        try {
            provider.prepareForBackgroundUse()
            null
        } catch (failure: Throwable) {
            failure
        }

    private suspend fun settled(outcome: CompletableDeferred<Throwable?>): Boolean =
        withTimeoutOrNull(5_000) { outcome.await(); true } == true

    private fun spoolingStrategy(
        inMemorySizeBytes: Int = 1,
        deleteFilesOnClose: Boolean = true,
    ): StreamingStrategy.RepeatableFileStore =
        StreamingStrategy.RepeatableFileStore(
            inMemorySizeBytes = inMemorySizeBytes,
            tempDirectory = tempDir.toString(),
            deleteFilesOnClose = deleteFilesOnClose,
        )

    private fun spoolingFiles(): List<Path> =
        Files.list(tempDir).use { paths ->
            paths.filter { it.name.startsWith("muse-stream-") }.toList()
        }
}

/**
 * Serves [payload] and reports reads that happen after [close]: `ByteArrayInputStream.close` is a
 * no-op, so this fake is what makes a real disconnect observable.
 */
private class CloseSensitiveInputStream(payload: String) : InputStream() {
    private val delegate = ByteArrayInputStream(payload.toByteArray())
    private val closed = AtomicBoolean(false)
    private val closedReads = AtomicBoolean(false)
    private var readCount = 0

    val isClosed: Boolean get() = closed.get()

    val readAfterClose: Boolean get() = closedReads.get()

    val readCalls: Int get() = readCount

    override fun read(): Int {
        if (closed.get()) {
            closedReads.set(true)
            throw IOException("stream closed")
        }
        readCount++
        return delegate.read()
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (closed.get()) {
            closedReads.set(true)
            throw IOException("stream closed")
        }
        readCount++
        return delegate.read(buffer, offset, length)
    }

    override fun close() {
        closed.set(true)
    }
}

/**
 * Serves [payload] and then blocks the next read until the stream is closed or the test completes the
 * body. A blocked read fails only when the stream is closed, so nothing but a real close unwinds it.
 */
private class BlockingSuffixInputStream(payload: String) : InputStream() {
    private val bytes = payload.toByteArray()
    private var position = 0
    private val readBlocked = CountDownLatch(1)
    private val readReleased = CountDownLatch(1)
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    fun awaitReadBlocked(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = readBlocked.await(timeout, unit)

    /** Simulates a client that finishes the body without disconnecting. */
    fun completeRead() {
        readReleased.countDown()
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position < bytes.size) {
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        readBlocked.countDown()
        readReleased.await()
        if (closed.get()) throw IOException("the request body stream was closed during background preparation")
        return -1
    }

    override fun close() {
        closed.set(true)
        readReleased.countDown()
    }
}

/**
 * Serves [payload] and cancels the preparation when the copy reaches the end of the body, which is the
 * window between a completed copy and the publication of its detached backing.
 */
private class CancellingAtEndInputStream(
    payload: String,
    private val cancelPreparation: () -> Unit,
) : InputStream() {
    private val bytes = payload.toByteArray()
    private var position = 0
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position < bytes.size) {
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        cancelPreparation()
        return -1
    }

    override fun close() {
        closed.set(true)
    }
}

/** Serves [payload] and then fails every read with [failure]. */
private class FailingInputStream(
    payload: String,
    private val failure: IOException,
) : InputStream() {
    private val bytes = payload.toByteArray()
    private var position = 0

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= bytes.size) throw failure
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
}

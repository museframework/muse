package dev.museframework.engine.api.streaming

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingResourceLeaseTest {
    @Test
    fun `closing response claim cannot close an accepted branch provider`() {
        val root = StreamingResourceRegistry()
        val branch = StreamingResourceRegistry()
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("late".toByteArray())
        root.register(provider)
        branch.register(provider)
        val response = root.retain(listOf(provider))
        root.releaseAll()
        response.close()
        assertFalse(provider.isClosed)
        assertEquals("late", provider.openCursor().use { String(it.readBytes()) })
        branch.releaseAll()
        assertTrue(provider.isClosed)
        response.close()
    }

    @Test
    fun `every root response branch completion order cleans exactly once at the last owner`() {
        val orders = listOf(
            listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
        )
        orders.forEach { order ->
            val root = StreamingResourceRegistry()
            val branch = StreamingResourceRegistry()
            val provider = RecordingProvider()
            root.register(provider)
            branch.register(provider)
            val response = root.retain(listOf(provider))
            val completions = listOf(root::releaseAll, response::close, branch::releaseAll)
            order.take(2).forEach {
                completions[it]()
                assertEquals(0, provider.closeCount.get())
                assertEquals(0, provider.releaseCount.get())
            }
            completions[order.last()]()
            completions.forEach { it() }
            assertCleanedOnce(provider)
        }
    }

    @Test
    fun `lease deduplicates aliases but retains equal distinct identities`() {
        val root = StreamingResourceRegistry()
        val first = RecordingProvider()
        val second = RecordingProvider()
        assertEquals(first, second)
        root.register(first)
        root.register(second)
        val lease = root.retain(listOf(first, first, second))
        root.releaseAll()
        assertEquals(0, first.closeCount.get())
        assertEquals(0, second.closeCount.get())
        lease.close()
        lease.close()
        assertCleanedOnce(first)
        assertCleanedOnce(second)
    }

    @Test
    fun `failed acquisition of an unowned equal identity leaves no provisional claims`() {
        val root = StreamingResourceRegistry()
        val owned = root.register(RecordingProvider())
        val unowned = RecordingProvider()
        assertFailsWith<IllegalArgumentException> { root.retain(listOf(owned, unowned)) }
        root.releaseAll()
        assertCleanedOnce(owned)
        assertEquals(0, unowned.closeCount.get())
        assertFalse(unowned.isManaged)
    }

    @Test
    fun `failed acquisition of a closed provider leaves earlier identities unretained`() {
        val root = StreamingResourceRegistry()
        val healthy = root.register(RecordingProvider())
        val closed = root.register(RecordingProvider())
        closed.close()
        assertFailsWith<IllegalStateException> { root.retain(listOf(healthy, closed)) }
        root.releaseAll()
        assertCleanedOnce(healthy)
        assertEquals(1, closed.releaseCount.get())
        assertFalse(closed.isManaged)
    }

    @Test
    fun `released registry rejects even an empty retained claim`() {
        val root = StreamingResourceRegistry()
        root.retain(emptyList()).close()
        root.releaseAll()
        assertFailsWith<IllegalStateException> { root.retain(emptyList()) }
    }

    @Test
    fun `lease cleanup aggregates failures and cleans every provider only once`() {
        val root = StreamingResourceRegistry()
        val failing = root.register(RecordingProvider(failCleanup = true))
        val healthy = root.register(RecordingProvider())
        val lease = root.retain(listOf(failing, healthy))
        root.releaseAll()
        val failure = assertFailsWith<StreamingCleanupException> { lease.close() }
        assertEquals(2, failure.failures.size)
        assertEquals(setOf("close failure", "release failure"), failure.failures.map { it.message }.toSet())
        lease.close()
        assertCleanedOnce(failing)
        assertCleanedOnce(healthy)
    }

    @Test
    fun `release that wins the closure probe is not resurrected by retain`() {
        val validating = CountDownLatch(1)
        val continueValidation = CountDownLatch(1)
        val released = CountDownLatch(1)
        val root = StreamingResourceRegistry()
        val provider = root.register(RecordingProvider(onCheckClosed = {
            validating.countDown()
            assertTrue(continueValidation.await(10, TimeUnit.SECONDS))
        }))
        val executor = Executors.newFixedThreadPool(2)
        val acquisition =
            executor.submit<Result<StreamingResourceLease>> {
                runCatching { root.retain(listOf(provider)) }
            }
        var outcome: Result<StreamingResourceLease>? = null
        try {
            assertTrue(validating.await(10, TimeUnit.SECONDS))
            val release = executor.submit {
                root.releaseAll()
                released.countDown()
            }
            assertTrue(released.await(10, TimeUnit.SECONDS))
            continueValidation.countDown()
            outcome = acquisition.get(10, TimeUnit.SECONDS)
            release.get(10, TimeUnit.SECONDS)
        } finally {
            continueValidation.countDown()
            // Ownership cleanup runs before any assertion, and each teardown step runs
            // even if an earlier one fails, so a failed check can never leave an
            // unexpected lease behind as the last strong claim.
            try {
                (outcome ?: acquisition.get(10, TimeUnit.SECONDS)).getOrNull()?.close()
            } finally {
                try {
                    root.releaseAll()
                } finally {
                    executor.shutdownNow()
                }
            }
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        val completion = checkNotNull(outcome)
        assertTrue(completion.isFailure)
        assertIs<IllegalStateException>(completion.exceptionOrNull())
        assertCleanedOnce(provider)
        assertFailsWith<IllegalStateException> { root.retain(listOf(provider)) }
    }

    @Test
    fun `retain aborts when the closure probe re-enters source release`() {
        val source = StreamingResourceRegistry()
        val other = StreamingResourceRegistry()
        val reentered = AtomicBoolean(false)
        lateinit var provider: RecordingProvider
        provider =
            RecordingProvider(onCheckClosed = {
                if (reentered.compareAndSet(false, true)) source.releaseAll()
            })
        source.register(provider)
        other.register(provider)
        val rejection =
            try {
                val releasedSource = assertRetainRejected(source, provider)
                assertTrue(provider.isManaged)
                assertEquals(0, provider.closeCount.get())
                assertRetainRejected(source, provider)
                releasedSource
            } finally {
                other.releaseAll()
            }
        assertCleanedOnce(provider)
        assertIs<IllegalStateException>(rejection)
    }

    @Test
    fun `active registry cannot retain an identity whose last owner cleanup is in progress`() {
        val cleaning = CountDownLatch(1)
        val continueCleanup = CountDownLatch(1)
        val owner = StreamingResourceRegistry()
        val other = StreamingResourceRegistry()
        val provider =
            owner.register(RecordingProvider(onClose = {
                cleaning.countDown()
                assertTrue(continueCleanup.await(10, TimeUnit.SECONDS))
            }))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val release = executor.submit { owner.releaseAll() }
            assertTrue(cleaning.await(10, TimeUnit.SECONDS))

            other.register(provider)
            assertRetainRejected(other, provider)

            continueCleanup.countDown()
            release.get(10, TimeUnit.SECONDS)
            assertCleanedOnce(provider)
            assertRetainRejected(other, provider)
            assertFalse(provider.isManaged)
        } finally {
            continueCleanup.countDown()
            try {
                other.releaseAll()
            } finally {
                executor.shutdownNow()
            }
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    @Test
    fun `released source registry rejects retain while its last owner is cleaning up`() {
        val cleaning = CountDownLatch(1)
        val continueCleanup = CountDownLatch(1)
        val root = StreamingResourceRegistry()
        val provider = root.register(RecordingProvider(onClose = {
            cleaning.countDown()
            assertTrue(continueCleanup.await(10, TimeUnit.SECONDS))
        }))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val release = executor.submit { root.releaseAll() }
            assertTrue(cleaning.await(10, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { root.retain(listOf(provider)) }
            continueCleanup.countDown()
            release.get(10, TimeUnit.SECONDS)
            assertCleanedOnce(provider)
        } finally {
            continueCleanup.countDown()
            try {
                root.releaseAll()
            } finally {
                executor.shutdownNow()
            }
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    @Test
    fun `concurrent lease closes release exactly one claim`() {
        val root = StreamingResourceRegistry()
        val provider = root.register(RecordingProvider())
        val lease = root.retain(listOf(provider))
        root.releaseAll()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val closes = List(2) {
                executor.submit {
                    ready.countDown()
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    lease.close()
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            closes.forEach { it.get(10, TimeUnit.SECONDS) }
            assertCleanedOnce(provider)
        } finally {
            start.countDown()
            executor.shutdownNow()
            // The lease is idempotent: closing it here keeps the claim out of the
            // global ownership counts even if a worker was cancelled before its own
            // close, and cannot resurrect anything once both workers finished.
            try {
                lease.close()
            } finally {
                root.releaseAll()
            }
        }
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    private fun assertCleanedOnce(provider: RecordingProvider) {
        assertEquals(1, provider.closeCount.get())
        assertEquals(1, provider.releaseCount.get())
        assertFalse(provider.isManaged)
    }

    /**
     * Asserts that acquisition is rejected, closing any unexpected lease so a
     * regression cannot leak a claim into the global ownership counts, and returns
     * the failure so callers can pin the documented type where the guard is contractual.
     */
    private fun assertRetainRejected(
        registry: StreamingResourceRegistry,
        provider: MuseCursorProvider<out MuseCursor>,
    ): Throwable {
        val attempt = runCatching { registry.retain(listOf(provider)) }
        attempt.getOrNull()?.close()
        return requireNotNull(attempt.exceptionOrNull()) { "retain must be rejected" }
    }

    private class RecordingProvider(
        private val failCleanup: Boolean = false,
        private val onCheckClosed: () -> Unit = {},
        private val onClose: () -> Unit = {},
    ) : MuseCursorProvider<MuseCursor> {
        val closeCount = AtomicInteger()
        val releaseCount = AtomicInteger()
        override val isClosed: Boolean
            get() {
                onCheckClosed()
                return closeCount.get() > 0
            }
        override fun openCursor(): MuseCursor = error("Retention must not open cursors")
        override fun close() {
            closeCount.incrementAndGet()
            onClose()
            if (failCleanup) error("close failure")
        }
        override fun releaseResources() {
            releaseCount.incrementAndGet()
            if (failCleanup) error("release failure")
        }
        override fun equals(other: Any?): Boolean = other is RecordingProvider
        override fun hashCode(): Int = 0
    }
}

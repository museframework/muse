package dev.museframework.connectors.objectstore.internal

import dev.museframework.engine.api.event.TypedValue
import dev.museframework.objectstore.api.config.ObjectStoreConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObjectStoreLockTest {
    @Test
    fun `zero and finite waits time out while unbounded wait resumes and cancellation skips mutation`() = runBlocking {
        H2ObjectStore(ObjectStoreConfig("lock", persistent = false), "jdbc:h2:mem:${UUID.randomUUID()}").use { store ->
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = launch(Dispatchers.IO) {
                store.withLock {
                    locked.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Lock holder was not released" }
                }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { locked.await(5, TimeUnit.SECONDS) })
                assertFailsWith<TimeoutException> { store.withLock(Duration.ZERO) { Unit } }
                assertFailsWith<TimeoutException> { store.withLock(Duration.ofMillis(60)) { Unit } }

                val unlimited = async(Dispatchers.IO) { store.withLock(null) { "acquired" } }
                val waiterStarted = CountDownLatch(1)
                val cancelled = async(Dispatchers.IO) {
                    waiterStarted.countDown()
                    store.withLock(null) { store.put("cancelled", TypedValue.of("value"), false) }
                }
                assertTrue(withContext(Dispatchers.IO) { waiterStarted.await(5, TimeUnit.SECONDS) })
                delay(50)
                assertFalse(unlimited.isCompleted)
                cancelled.cancelAndJoin()

                release.countDown()
                assertEquals("acquired", withTimeout(2_000) { unlimited.await() })
                assertFalse(store.contains("cancelled"))
            } finally {
                release.countDown()
                holder.cancelAndJoin()
            }
        }
    }
}

package dev.museframework.engine.dispatcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoroutineDispatcherProviderTest {

    @AfterEach
    fun `clear system property`() {
        System.clearProperty("muse.coroutine.dispatcher")
    }

    @Nested
    @DisplayName("when no property is set")
    inner class DefaultBehavior {

        @Test
        fun `should return virtual thread dispatcher`() {
            val dispatcher = CoroutineDispatcherProvider.fromSystemProperty()
            val name = dispatcher.toString()
            assertTrue(
                name.contains("LimitingDispatcher") || name.contains("VirtualThread") ||
                    name.contains("ThreadPerTaskExecutor"),
                "Expected virtual thread dispatcher, got: $name"
            )
        }
    }

    @Nested
    @DisplayName("when muse.coroutine.dispatcher=virtual")
    inner class ExplicitVirtual {

        @Test
        fun `should return virtual thread dispatcher`() {
            System.setProperty("muse.coroutine.dispatcher", "virtual")
            val dispatcher = CoroutineDispatcherProvider.fromSystemProperty()
            val name = dispatcher.toString()
            assertTrue(
                name.contains("LimitingDispatcher") || name.contains("VirtualThread") ||
                    name.contains("ThreadPerTaskExecutor"),
                "Expected virtual thread dispatcher, got: $name"
            )
        }
    }

    @Nested
    @DisplayName("when muse.coroutine.dispatcher=platform")
    inner class ExplicitPlatform {

        @Test
        fun `should return Dispatchers Default`() {
            System.setProperty("muse.coroutine.dispatcher", "platform")
            val dispatcher = CoroutineDispatcherProvider.fromSystemProperty()
            assertEquals(Dispatchers.Default.toString(), dispatcher.toString())
        }
    }

    @Nested
    @DisplayName("coroutine execution on virtual dispatcher")
    inner class VirtualExecution {

        @Test
        fun `virtual thread dispatcher should execute coroutines`() = runBlocking {
            val dispatcher = CoroutineDispatcherProvider.virtual()
            var executed = false
            val job = launch(dispatcher) {
                delay(10)
                executed = true
            }
            job.join()
            assertTrue(executed)
        }
    }
}

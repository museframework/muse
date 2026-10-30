package dev.museframework.dsl.builder

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ParallelForEachBuilderTest {
    @Test
    fun `should reject non-positive timeout`() {
        listOf(0L, -1L).forEach { invalidTimeout ->
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    ParallelForEachBuilder()
                        .apply { timeout = invalidTimeout }
                        .build()
                }

            assertEquals(
                "Parallel For Each timeout must be greater than 0 when configured, but was $invalidTimeout",
                exception.message,
            )
        }
    }

    @Test
    fun `should reject non-positive max concurrency`() {
        listOf(0, -1).forEach { invalidMaxConcurrency ->
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    ParallelForEachBuilder()
                        .apply { maxConcurrency = invalidMaxConcurrency }
                        .build()
                }

            assertEquals(
                "Parallel For Each maxConcurrency must be greater than 0 when configured, but was $invalidMaxConcurrency",
                exception.message,
            )
        }
    }

    @Test
    fun `should accept absent and positive execution limits`() {
        val defaults = ParallelForEachBuilder().build()
        val configured =
            ParallelForEachBuilder()
                .apply {
                    timeout = 1L
                    maxConcurrency = 1
                }.build()

        assertNull(defaults.timeout)
        assertNull(defaults.maxConcurrency)
        assertEquals(1L, configured.timeout)
        assertEquals(1, configured.maxConcurrency)
    }
}

package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.file.api.config.FileListenerConfig
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSchedulingTest {
    private val location = SourceLocation(1, 1)

    @Test
    fun `fixed frequency without a unit stays in milliseconds`() {
        assertEquals(2_000L, pollingFrequencyMillis(listener(fixedFrequency(frequency = "2000"))))
    }

    @Test
    fun `fixed frequency converts timeUnit to milliseconds`() {
        assertEquals(
            1_000L,
            pollingFrequencyMillis(listener(fixedFrequency(frequency = "1", timeUnit = "SECONDS"))),
        )
    }

    @Test
    fun `cron scheduling is rejected`() {
        val cron =
            RawElement(
                namespace = "",
                tag = "cron",
                attrs = mapOf("expression" to "0 0 * * * ?"),
                children = emptyList(),
                location = location,
            )
        val failure =
            assertFailsWith<IllegalArgumentException> {
                pollingFrequencyMillis(listener(cron, fixedFrequency(frequency = "2000")))
            }
        assertTrue(failure.message.orEmpty().contains("cron"))
    }

    @Test
    fun `non numeric fixed frequency is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            pollingFrequencyMillis(listener(fixedFrequency(frequency = "abc")))
        }
    }

    @Test
    fun `startDelay is kept apart from the polling frequency`() {
        val element = listener(fixedFrequency(frequency = "2000", startDelay = "5000"))
        assertEquals(2_000L, pollingFrequencyMillis(element))
        assertEquals(5_000L, startDelayMillis(element))
    }

    @Test
    fun `startDelay uses the fixed frequency timeUnit`() {
        val element = listener(fixedFrequency(frequency = "1", timeUnit = "SECONDS", startDelay = "5"))
        assertEquals(1_000L, pollingFrequencyMillis(element))
        assertEquals(5_000L, startDelayMillis(element))
    }

    @Test
    fun `missing startDelay is zero`() {
        assertEquals(0L, startDelayMillis(listener(fixedFrequency(frequency = "2000"))))
    }

    @Test
    fun `negative startDelay is rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                startDelayMillis(listener(fixedFrequency(frequency = "2000", startDelay = "-1")))
            }
        assertTrue(failure.message.orEmpty().contains("startDelay"))
    }

    @Test
    fun `non numeric startDelay is rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                startDelayMillis(listener(fixedFrequency(frequency = "2000", startDelay = "abc")))
            }
        assertTrue(failure.message.orEmpty().contains("startDelay"))
    }

    @Test
    fun `listener maps and emits a start delay`() {
        val listener =
            FileListenerMapper.map(
                listener(fixedFrequency(frequency = "2", timeUnit = "SECONDS", startDelay = "5")),
                MappingContext(BabelfishRegistry(), Path.of("test.xml")),
            )
        assertEquals(2_000L, listener.pollingFrequencyMillis)
        assertEquals(5_000L, listener.startDelayMillis)

        val code = FileListenerCodeEmitter.emit(listener, CodegenContext("dev.example"))
        assertContains(code, "pollingFrequencyMillis = 2000")
        assertContains(code, "startDelayMillis = 5000")
    }

    @Test
    fun `emitter omits a zero start delay`() {
        val code =
            FileListenerCodeEmitter.emit(
                FileListenerConfig(connectionConfig = ConfigRef.ByName("files")),
                CodegenContext("dev.example"),
            )
        assertFalse(code.contains("startDelayMillis"))
    }

    @Test
    fun `missing scheduling falls back to one second`() {
        assertEquals(
            1_000L,
            pollingFrequencyMillis(
                RawElement(
                    namespace = "file",
                    tag = "listener",
                    attrs = emptyMap(),
                    children = emptyList(),
                    location = location,
                ),
            ),
        )
    }

    private fun listener(vararg strategyChildren: RawElement): RawElement =
        RawElement(
            namespace = "file",
            tag = "listener",
            attrs = mapOf("config-ref" to "files"),
            children =
                listOf(
                    RawElement(
                        namespace = "",
                        tag = "scheduling-strategy",
                        attrs = emptyMap(),
                        children = strategyChildren.toList(),
                        location = location,
                    ),
                ),
            location = location,
        )

    private fun fixedFrequency(
        frequency: String,
        timeUnit: String? = null,
        startDelay: String? = null,
    ): RawElement {
        val attrs = mutableMapOf("frequency" to frequency)
        if (timeUnit != null) attrs["timeUnit"] = timeUnit
        if (startDelay != null) attrs["startDelay"] = startDelay
        return RawElement(
            namespace = "",
            tag = "fixed-frequency",
            attrs = attrs,
            children = emptyList(),
            location = location,
        )
    }
}

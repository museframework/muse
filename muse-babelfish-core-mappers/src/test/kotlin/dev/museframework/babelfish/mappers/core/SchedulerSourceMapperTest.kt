package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.dsl.api.config.CronStrategy
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SchedulerTimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class SchedulerSourceMapperTest {

    private fun createMappingContext(): MappingContext =
        MappingContext(BabelfishRegistry(), Paths.get("test.xml"))

    @Test
    fun `maps fixed frequency scheduler attributes`() {
        val scheduler =
            RawElement(
                namespace = "",
                tag = "scheduler",
                attrs = mapOf(
                    "name" to "Scheduler",
                    "disallowConcurrentExecution" to "true",
                ),
                children = listOf(
                    RawElement(
                        namespace = "",
                        tag = "scheduling-strategy",
                        attrs = emptyMap(),
                        children = listOf(
                            RawElement(
                                namespace = "",
                                tag = "fixed-frequency",
                                attrs = mapOf(
                                    "frequency" to "15",
                                    "timeUnit" to "SECONDS",
                                    "startDelay" to "10",
                                ),
                                children = emptyList(),
                                location = SourceLocation(1, 1),
                            ),
                        ),
                        location = SourceLocation(1, 1),
                    ),
                ),
                location = SourceLocation(1, 1),
            )

        val config = SchedulerSourceMapper.map(scheduler, createMappingContext())

        assertEquals("Scheduler", config.docName)
        assertTrue(config.disallowConcurrentExecution)
        val strategy = config.schedulingStrategy as FixedFrequencyStrategy
        assertEquals("15", strategy.frequency)
        assertEquals("10", strategy.startDelay)
        assertEquals(SchedulerTimeUnit.SECONDS, strategy.timeUnit)
    }

    @Test
    fun `maps cron scheduler attributes`() {
        val scheduler =
            RawElement(
                namespace = "",
                tag = "scheduler",
                attrs = mapOf("name" to "Scheduler"),
                children = listOf(
                    RawElement(
                        namespace = "",
                        tag = "scheduling-strategy",
                        attrs = emptyMap(),
                        children = listOf(
                            RawElement(
                                namespace = "",
                                tag = "cron",
                                attrs = mapOf(
                                    "expression" to "0 0 12 * * ?",
                                    "timeZone" to "America/Los_Angeles",
                                ),
                                children = emptyList(),
                                location = SourceLocation(1, 1),
                            ),
                        ),
                        location = SourceLocation(1, 1),
                    ),
                ),
                location = SourceLocation(1, 1),
            )

        val config = SchedulerSourceMapper.map(scheduler, createMappingContext())

        val strategy = config.schedulingStrategy as CronStrategy
        assertEquals("0 0 12 * * ?", strategy.expression)
        assertEquals("America/Los_Angeles", strategy.timeZone)
    }

    // --- KotlinPoet Emitter Tests ---

    @Test
    fun `escapes dollar sign in frequency value`() {
        val ctx = CodegenContext("com.test")
        val code =
            KotlinPoetSchedulerSourceCodeEmitter.emit(
                SchedulerConfig(
                    docName = "Scheduler",
                    schedulingStrategy = FixedFrequencyStrategy(
                        frequency = "\${app.scheduler.frequency_ms}",
                        startDelay = "0",
                    ),
                ),
                ctx,
            )

        val d = "$"
        val expected = "frequency = \"${d}{'${d}'}{app.scheduler.frequency_ms}\""
        assertTrue(code.contains(expected))
    }

    @Test
    fun `emits fixed frequency scheduler DSL with KotlinPoet`() {
        val ctx = CodegenContext("com.test")
        val code =
            KotlinPoetSchedulerSourceCodeEmitter.emit(
                SchedulerConfig(
                    docName = "Scheduler",
                    disallowConcurrentExecution = true,
                    schedulingStrategy = FixedFrequencyStrategy(
                        frequency = "15",
                        startDelay = "10",
                        timeUnit = SchedulerTimeUnit.SECONDS,
                    ),
                ),
                ctx,
            )

        assertTrue(code.contains("scheduler {"))
        assertTrue(code.contains("disallowConcurrentExecution = true"))
        assertTrue(code.contains("fixedFrequency {"))
        assertTrue(code.contains("frequency = \"15\""))
        assertTrue(code.contains("startDelay = \"10\""))
        assertTrue(code.contains("timeUnit = SchedulerTimeUnit.SECONDS"))
    }

    @Test
    fun `emits cron scheduler DSL with KotlinPoet`() {
        val ctx = CodegenContext("com.test")
        val code =
            KotlinPoetSchedulerSourceCodeEmitter.emit(
                SchedulerConfig(
                    docName = "Scheduler",
                    schedulingStrategy = CronStrategy(
                        expression = "0 0 12 * * ?",
                        timeZone = "America/Los_Angeles",
                    ),
                ),
                ctx,
            )

        assertTrue(code.contains("scheduler {"))
        assertTrue(code.contains("cron {"))
        assertTrue(code.contains("expression = \"0 0 12 * * ?\""))
        assertTrue(code.contains("timeZone = \"America/Los_Angeles\""))
    }
}

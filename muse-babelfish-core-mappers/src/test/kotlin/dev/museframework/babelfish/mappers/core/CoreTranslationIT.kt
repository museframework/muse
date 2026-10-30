package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig
import dev.museframework.dsl.api.config.FixedFrequencyStrategy
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.LoggerConfig
import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SchedulerTimeUnit
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.flow.MuseFlow
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CoreTranslationIT {
    @Test
    fun `emits distinct configuration properties without optional doc metadata`() {
        val result = translate("unnamed-configuration-properties.xml")
        val configs = result.usedConfigs.values.map { assertIs<ConfigurationPropertiesConfig>(it) }

        assertEquals(listOf("application.properties", "secondary.properties"), configs.map { it.file })
        assertEquals(listOf("", ""), configs.map { it.docName })
        assertEquals(listOf("", ""), configs.map { it.docId })

        val code = emit(result)
        assertContains(code, "val unnamedConfig1 = ConfigurationPropertiesConfig(")
        assertContains(code, "val unnamedConfig2 = ConfigurationPropertiesConfig(")
        assertContains(code, "file = \"application.properties\"")
        assertContains(code, "file = \"secondary.properties\"")
    }

    @Test
    fun `translates and emits choice routing`() {
        val result = translate("choice-routing.xml")
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val choice = assertIs<ChoiceConfig>(flow.pipeline.single())

        assertNull(flow.source)
        assertEquals(2, choice.whenRoutes.size)
        assertEquals("#[attributes.queryParams.\"type\" == \"A\"]", choice.whenRoutes[0].expression)
        assertIs<SetPayloadConfig>(choice.whenRoutes[0].pipeline.single())
        assertEquals(1, choice.otherwise?.size)

        val code = emit(result)
        assertContains(code, "choice {")
        assertContains(code, "`when`(\"#[attributes.queryParams.\\\"type\\\" == \\\"A\\\"]\")")
        assertContains(code, "otherwise {")
        assertContains(code, "Route A")
        assertContains(code, "Route B")
        assertContains(code, "Default Route")
    }

    @Test
    fun `translates and emits foreach routing`() {
        val result = translate("foreach-iteration.xml")
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val forEach = assertIs<ForEachConfig>(flow.pipeline[0])

        assertNull(flow.source)
        assertEquals("#[payload['items']]", forEach.collection)
        assertEquals(2, forEach.batchSize)
        assertEquals("root", forEach.rootMessageVariableName)
        assertEquals("iteration", forEach.counterVariableName)
        assertIs<SetPayloadConfig>(forEach.pipeline.single())
        assertIs<SetPayloadConfig>(flow.pipeline[1])

        val code = emit(result)
        assertContains(code, "forEach {")
        assertContains(code, "collection = \"#[payload['items']]\"")
        assertContains(code, "batchSize = 2")
        assertContains(code, "rootMessageVariableName = \"root\"")
        assertContains(code, "counterVariableName = \"iteration\"")
        assertContains(code, "Processed: items completed")
    }

    @Test
    fun `translates and emits scatter-gather targets and route structure`() {
        val result = translate("scatter-gather.xml")
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val scatterGather = assertIs<ScatterGatherConfig>(flow.pipeline.single())
        val target = assertNotNull(scatterGather.targetConfig)

        assertEquals(500, scatterGather.timeout)
        assertEquals(2, scatterGather.maxConcurrency)
        assertEquals("count", target.target)
        assertEquals("#[sizeOf(payload)]", target.targetValue)
        assertEquals(2, scatterGather.routes.size)
        assertIs<SetPayloadConfig>(scatterGather.routes[0].pipeline.single())
        assertIs<SetPayloadConfig>(scatterGather.routes[1].pipeline.single())

        val code = emit(result)
        assertContains(code, "scatterGather {")
        assertContains(code, "timeout = 500")
        assertContains(code, "maxConcurrency = 2")
        assertContains(code, "target = \"count\"")
        assertContains(code, "targetValue = \"#[sizeOf(payload)]\"")
        assertContains(code, "route {")
    }

    @Test
    fun `translates and emits parallel-foreach targets and pipeline structure`() {
        val result = translate("parallel-foreach.xml")
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val parallelForEach = assertIs<ParallelForEachConfig>(flow.pipeline.single())
        val target = assertNotNull(parallelForEach.targetConfig)

        assertEquals("#[[1,2,3]]", parallelForEach.collection)
        assertEquals(500, parallelForEach.timeout)
        assertEquals(2, parallelForEach.maxConcurrency)
        assertEquals("count", target.target)
        assertEquals("#[sizeOf(payload)]", target.targetValue)
        assertIs<SetPayloadConfig>(parallelForEach.pipeline.single())

        val code = emit(result)
        assertContains(code, "parallelForEach {")
        assertContains(code, "collection = \"#[[1,2,3]]\"")
        assertContains(code, "timeout = 500")
        assertContains(code, "maxConcurrency = 2")
        assertContains(code, "target = \"count\"")
        assertContains(code, "targetValue = \"#[sizeOf(payload)]\"")
    }

    @Test
    fun `translates and emits core processors inside error handlers`() {
        val result = translate("error-handling.xml")
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val choice = assertIs<ChoiceConfig>(flow.pipeline.single())
        val handler = assertNotNull(flow.errorHandler)
        val strategy = assertIs<ErrorHandlerStrategy.OnErrorContinue>(handler.strategies.single())

        assertIs<RaiseErrorConfig>(choice.whenRoutes.single().pipeline.single())
        assertEquals("MULE", strategy.errorType.namespace)
        assertEquals("EXPRESSION", strategy.errorType.identifier)
        assertIs<SetPayloadConfig>(strategy.pipeline.single())

        val code = emit(result)
        assertContains(code, "raiseError {")
        assertContains(code, "errorHandler {")
        assertContains(code, "onErrorContinue")
        assertContains(code, "ErrorType(\"MULE\", \"EXPRESSION\")")
    }

    @Test
    fun `translates and emits scheduler source`() {
        val result = translate("scheduler.xml")
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val scheduler = assertIs<SchedulerConfig>(flow.source)
        val strategy = assertIs<FixedFrequencyStrategy>(scheduler.schedulingStrategy)

        assertEquals("scheduler-flow", flow.name)
        assertEquals("5000", strategy.frequency)
        assertEquals("1000", strategy.startDelay)
        assertEquals(SchedulerTimeUnit.MILLISECONDS, strategy.timeUnit)
        assertIs<LoggerConfig>(flow.pipeline.single())

        val code = emit(result)
        assertContains(code, "import dev.museframework.dsl.api.config.SchedulerTimeUnit")
        assertContains(code, "import dev.museframework.dsl.dsl.scheduler")
        assertContains(code, "scheduler {")
        assertContains(code, "fixedFrequency {")
        assertContains(code, "frequency = \"5000\"")
        assertContains(code, "startDelay = \"1000\"")
        assertContains(code, "timeUnit = SchedulerTimeUnit.MILLISECONDS")
    }

    private fun translate(fixtureName: String): TranslationResult {
        val registry = BabelfishRegistry().apply { CoreMapperProvider().registerMappings(this) }
        return MuseBabelfish.translateXmlToMuseKotlinDsl(
            Path.of("src/test/resources/mule/core/$fixtureName"),
            registry,
        )
    }

    private fun emit(result: TranslationResult): String =
        KotlinCodeEmitter()
            .apply { CoreMapperProvider().registerEmitters(this) }
            .emit(result, "dev.museframework.babelfish.generated")
}

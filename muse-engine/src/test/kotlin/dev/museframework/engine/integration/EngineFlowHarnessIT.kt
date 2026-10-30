package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class EngineFlowHarnessIT {
    @Test
    fun `should deliver input and return the final engine event`() =
        runTest {
            val factory =
                MuseComponentFactory().apply {
                    registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
                    registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
                }
            val flow =
                flow("harness-final-event") {
                    setVariable("originalPayload") {
                        value = "#[payload]"
                    }
                    setPayload {
                        value = "processed"
                    }
                }
            val input =
                MuseEvent(
                    payload = TypedValue.of("input"),
                    attributes = TypedValue.of(mapOf("requestId" to "request-1")),
                    correlationId = "correlation-1",
                )

            val result = EngineFlowHarness(this, flow, factory).execute(input)

            assertEquals("processed", result.payload?.value)
            assertEquals("input", result.variables["originalPayload"]?.value)
            assertEquals(mapOf("requestId" to "request-1"), result.attributes?.value)
            assertEquals("correlation-1", result.correlationId)
            assertNull(result.error)
        }

    @Test
    fun `should complete processor delays using virtual time`() =
        runTest {
            val factory = MuseComponentFactory()
            factory.registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context ->
                    delay(10_000)
                    val event = context.event.copy(payload = TypedValue.of("delayed"))
                    ProcessorResult.Success(context.withEvent(event))
                }
            }
            val flow =
                flow("harness-virtual-delay") {
                    processor(CaptureConfig(docName = "Delay", docId = "delay"))
                }

            val result = EngineFlowHarness(this, flow, factory).execute()

            assertEquals("delayed", result.payload?.value)
            assertEquals(10_000, currentTime)
        }

    @Test
    fun `should propagate processor exceptions and clean up before another execution`() =
        runTest {
            val failingFactory = MuseComponentFactory()
            failingFactory.registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) {
                    throw IllegalStateException("processor failed")
                }
            }
            val failingFlow =
                flow("harness-failure") {
                    processor(CaptureConfig(docName = "Fail", docId = "fail"))
                }

            val exception =
                assertFailsWith<IllegalStateException> {
                    EngineFlowHarness(this, failingFlow, failingFactory).execute()
                }
            assertEquals("processor failed", exception.message)

            val succeedingFactory = MuseComponentFactory()
            succeedingFactory.registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { context ->
                    ProcessorResult.Success(context)
                }
            }
            val succeedingFlow =
                flow("harness-after-failure") {
                    processor(CaptureConfig(docName = "Succeed", docId = "succeed"))
                }

            val result = EngineFlowHarness(this, succeedingFlow, succeedingFactory).execute()

            assertNull(result.error)
        }
}


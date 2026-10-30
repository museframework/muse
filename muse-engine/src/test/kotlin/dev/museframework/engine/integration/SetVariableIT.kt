package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SetVariableIT {
    @Test
    fun `should set variable with literal value`() =
        runTest {
            val flow =
                flow("set-variable-literal-flow") {
                    setVariable("httpStatus") {
                        docName = "Set HTTP Status"
                        value = "202"
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            val variable = result.variables["httpStatus"]
            assertNotNull(variable)
            assertEquals("202", variable.value)
        }

    @Test
    fun `should set variable with expression value`() =
        runTest {
            val flow =
                flow("set-variable-expr-flow") {
                    setVariable("capturedPayload") {
                        docName = "Capture Payload"
                        value = "#[payload]"
                    }
                }
            val input = MuseEvent(payload = TypedValue.of("request-payload"))

            val result = EngineFlowHarness(this, flow, factory()).execute(input)

            val variable = result.variables["capturedPayload"]
            assertNotNull(variable)
            assertEquals("request-payload", variable.value)
        }

    @Test
    fun `should set multiple variables`() =
        runTest {
            val flow =
                flow("set-variable-multi-flow") {
                    setVariable("var1") {
                        docName = "Set Var 1"
                        value = "first"
                    }
                    setVariable("var2") {
                        docName = "Set Var 2"
                        value = "second"
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("first", result.variables["var1"]?.value)
            assertEquals("second", result.variables["var2"]?.value)
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
        }
}

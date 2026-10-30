package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.RemoveVariableConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.removeVariable
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.components.processor.removeVariableProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RemoveVariableIT {
    @Test
    fun `should remove existing variable`() =
        runTest {
            val flow =
                flow("remove-variable-test") {
                    setVariable("toRemove") {
                        docName = "Set Variable"
                        value = "will-be-removed"
                    }
                    setVariable("toKeep") {
                        docName = "Set Keep Variable"
                        value = "should-stay"
                    }
                    removeVariable("toRemove") {
                        docName = "Remove Variable"
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertFalse(result.variables.containsKey("toRemove"))
            assertEquals("should-stay", result.variables["toKeep"]?.value)
        }

    @Test
    fun `should not error when removing non-existent variable`() =
        runTest {
            val flow =
                flow("remove-nonexistent-variable") {
                    setPayload {
                        docName = "Set Payload"
                        value = "test-payload"
                    }
                    removeVariable("nonExistent") {
                        docName = "Remove Non-Existent"
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("test-payload", result.payload?.value)
            assertNull(result.error)
        }

    @Test
    fun `should handle multiple remove-variable operations`() =
        runTest {
            val flow =
                flow("remove-multiple-variables") {
                    setVariable("var1") {
                        docName = "Set Var1"
                        value = "value1"
                    }
                    setVariable("var2") {
                        docName = "Set Var2"
                        value = "value2"
                    }
                    setVariable("var3") {
                        docName = "Set Var3"
                        value = "value3"
                    }
                    removeVariable("var1") {
                        docName = "Remove Var1"
                    }
                    removeVariable("var3") {
                        docName = "Remove Var3"
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertFalse(result.variables.containsKey("var1"))
            assertEquals("value2", result.variables["var2"]?.value)
            assertFalse(result.variables.containsKey("var3"))
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
            registerProcessor(RemoveVariableConfig::class, removeVariableProcessorBuilder)
        }
}

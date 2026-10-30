package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.transform
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.components.processor.transformProcessorBuilder
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class TransformIT {
    @Test
    fun `should transform payload and set variables`() =
        runTest {
            val flow =
                flow("transform-test-flow") {
                    transform {
                        docName = "Transform Message"
                        setPayload =
                            DwExpression.Inline(
                                """
                                %dw 2.0
                                output application/java
                                ---
                                { kl: "payload-value" }
                                """.trimIndent(),
                            )
                        setVariable(
                            "myVar",
                            DwExpression.Inline(
                                """
                                %dw 2.0
                                output application/java
                                ---
                                { inline: "variable-value" }
                                """.trimIndent(),
                            ),
                        )
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            val payload = assertIs<Map<*, *>>(result.payload?.value)
            assertEquals("payload-value", payload["kl"])

            val variable = assertNotNull(result.variables["myVar"])
            val variableValue = assertIs<Map<*, *>>(variable.value)
            assertEquals("variable-value", variableValue["inline"])
        }

    @Test
    fun `should set attributes`() =
        runTest {
            val flow =
                flow("transform-attributes-flow") {
                    transform {
                        docName = "Set Attributes"
                        setAttributes =
                            DwExpression.Inline(
                                """
                                %dw 2.0
                                output application/java
                                ---
                                { ss: "attribute-value" }
                                """.trimIndent(),
                            )
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            val attributes = assertIs<Map<*, *>>(result.attributes?.value)
            assertEquals("attribute-value", attributes["ss"])
        }

    @Test
    fun `should preserve payload when only variables are set`() =
        runTest {
            val flow =
                flow("transform-vars-only-flow") {
                    transform {
                        docName = "Set Variable Only"
                        setVariable(
                            "onlyVar",
                            DwExpression.Inline(
                                """
                                %dw 2.0
                                output application/java
                                ---
                                "just-a-variable"
                                """.trimIndent(),
                            ),
                        )
                    }
                }
            val input = MuseEvent(payload = TypedValue.of("input-payload"))

            val result = EngineFlowHarness(this, flow, factory()).execute(input)

            assertEquals("input-payload", result.payload?.value)
            assertEquals("just-a-variable", result.variables["onlyVar"]?.value)
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(TransformConfig::class, transformProcessorBuilder)
        }
}

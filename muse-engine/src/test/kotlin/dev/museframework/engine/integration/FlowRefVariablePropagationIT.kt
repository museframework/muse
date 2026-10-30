package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.flowRef
import dev.museframework.dsl.dsl.subFlow
import dev.museframework.dsl.dsl.transform
import dev.museframework.engine.components.processor.transformProcessorBuilder
import dev.museframework.engine.execution.policy.flowRefPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FlowRefVariablePropagationIT {
    @Test
    fun `should propagate variable set in referenced flow back to calling flow`() =
        runTest {
            val referencedFlow =
                subFlow("referenced-flow") {
                    transform {
                        docName = "Set Variable In Referenced Flow"
                        setVariable(
                            "variableFromReferencedFlow",
                            DwExpression.Inline(
                                """
                                %dw 2.0
                                output application/java
                                ---
                                "set-in-referenced-flow"
                                """.trimIndent(),
                            ),
                        )
                    }
                }
            val mainFlow =
                flow("main-flow") {
                    transform {
                        docName = "Set Variable In Referencing Flow"
                        setVariable(
                            "variableFromReferencingFlow",
                            DwExpression.Inline(
                                """
                                %dw 2.0
                                output application/java
                                ---
                                "set-in-referencing-flow"
                                """.trimIndent(),
                            ),
                        )
                    }
                    flowRef(referencedFlow) {
                        docName = "Flow Reference"
                    }
                }

            val result =
                EngineFlowHarness(
                    this,
                    mainFlow,
                    factory(),
                    additionalFlows = listOf(referencedFlow),
                ).execute()

            assertEquals("set-in-referencing-flow", result.variables["variableFromReferencingFlow"]?.value)
            assertEquals("set-in-referenced-flow", result.variables["variableFromReferencedFlow"]?.value)
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(TransformConfig::class, transformProcessorBuilder)
            registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
        }
}

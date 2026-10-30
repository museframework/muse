package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.ChoiceConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for ChoiceConfig.
 *
 * Generates DSL code like:
 * ```
 * choice {
 *     `when`("#[payload == 'A']") {
 *         setPayload { value = "Route A" }
 *     }
 *     `when`("#[payload == 'B']") {
 *         setPayload { value = "Route B" }
 *     }
 *     otherwise {
 *         setPayload { value = "Default" }
 *     }
 * }
 * ```
 */
object KotlinPoetChoiceCodeEmitter : ElementCodeEmitter<ChoiceConfig> {

    override fun emit(element: ChoiceConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.choice")

        val codeBlock = CodeBlock.builder()
            .add("choice {\n")
            .apply {
                element.whenRoutes.forEach { route ->
                    add("    `when`(%S) {\n", route.expression)

                    val pipelineCode = ctx.emitPipelineElements(route.pipeline, "        ")
                    if (pipelineCode.isNotBlank()) {
                        add(pipelineCode)
                    }

                    add("    }\n")
                }

                element.otherwise?.let { otherwisePipeline ->
                    if (otherwisePipeline.isNotEmpty()) {
                        add("    otherwise {\n")

                        val pipelineCode = ctx.emitPipelineElements(otherwisePipeline, "        ")
                        if (pipelineCode.isNotBlank()) {
                            add(pipelineCode)
                        }

                        add("    }\n")
                    }
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }
}

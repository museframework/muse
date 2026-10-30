package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.ScatterGatherConfig
import com.squareup.kotlinpoet.CodeBlock

object KotlinPoetScatterGatherCodeEmitter : ElementCodeEmitter<ScatterGatherConfig> {

    override fun emit(element: ScatterGatherConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.scatterGather")

        val codeBlock = CodeBlock.builder()
            .add("scatterGather {\n")
            .apply {
                element.timeout?.let { add("    timeout = %L\n", it) }
                element.maxConcurrency?.let { add("    maxConcurrency = %L\n", it) }

                element.routes.forEach { route ->
                    add("    route {\n")
                    val pipelineCode = ctx.emitPipelineElements(route.pipeline, "        ")
                    if (pipelineCode.isNotBlank()) {
                        add(pipelineCode)
                    }
                    add("    }\n")
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }
}
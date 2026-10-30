package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.strategy.KotlinPoetErrorHandlerStrategyCodeEmitter
import dev.museframework.dsl.api.config.TryConfig
import com.squareup.kotlinpoet.CodeBlock

object KotlinPoetTryCodeEmitter : ElementCodeEmitter<TryConfig> {

    override fun emit(element: TryConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.tryScope")

        val codeBlock = CodeBlock.builder()
            .add("tryScope {\n")
            .apply {
                if (element.pipeline.isNotEmpty()) {
                    val pipelineCode = ctx.emitPipelineElements(element.pipeline, "    ")
                    if (pipelineCode.isNotBlank()) {
                        add(pipelineCode)
                    }
                }

                element.errorHandler?.let { eh ->
                    add("    errorHandler {\n")
                    eh.strategies.forEach { strategy ->
                        add("        ")
                        add(KotlinPoetErrorHandlerStrategyCodeEmitter.emitStrategy(strategy, ctx))
                        add("\n")
                    }
                    add("    }\n")
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }
}
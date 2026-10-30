package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.AsyncConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for AsyncConfig.
 *
 * Generates DSL code like:
 * ```
 * async {
 *     maxConcurrency = 4
 *     ... nested pipeline elements ...
 * }
 * ```
 */
object KotlinPoetAsyncCodeEmitter : ElementCodeEmitter<AsyncConfig> {

    override fun emit(element: AsyncConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.async")

        val codeBlock = CodeBlock.builder()
            .addStatement("async {")
            .apply {
                element.maxConcurrency?.let { maxConcurrency ->
                    addStatement("    maxConcurrency = %L", maxConcurrency)
                }
                if (element.pipeline.isNotEmpty()) {
                    add(ctx.emitPipelineElements(element.pipeline, "    "))
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }
}

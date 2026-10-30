package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.ParallelForEachConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for ParallelForEachConfig.
 *
 * Generates DSL code like:
 * ```
 * parallelForEach {
 *     collection = "#[payload.items]"
 *     timeout = 5000
 *     maxConcurrency = 4
 *     ... nested pipeline elements ...
 * }
 * ```
 */
object KotlinPoetParallelForEachCodeEmitter : ElementCodeEmitter<ParallelForEachConfig> {

    override fun emit(element: ParallelForEachConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.parallelForEach")

        val codeBlock = CodeBlock.builder()
            .addStatement("parallelForEach {")
            .apply {
                element.collection?.let { collection ->
                    addStatement("    collection = %S", collection)
                }
                element.timeout?.let { timeout ->
                    addStatement("    timeout = %L", timeout)
                }
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

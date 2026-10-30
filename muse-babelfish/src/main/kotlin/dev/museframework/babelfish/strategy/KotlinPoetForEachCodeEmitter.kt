package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.ForEachConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for ForEachConfig.
 *
 * Generates DSL code like:
 * ```
 * forEach {
 *     collection = "#[payload.items]"
 *     batchSize = 10
 *     rootMessageVariableName = "rootMsg"
 *     counterVariableName = "idx"
 *     ... nested pipeline elements ...
 * }
 * ```
 */
object KotlinPoetForEachCodeEmitter : ElementCodeEmitter<ForEachConfig> {

    override fun emit(element: ForEachConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.forEach")

        val codeBlock = CodeBlock.builder()
            .addStatement("forEach {")
            .apply {
                element.collection?.let { collection ->
                    addStatement("    collection = %S", collection)
                }
                if (element.batchSize != 1) {
                    addStatement("    batchSize = %L", element.batchSize)
                }
                if (element.rootMessageVariableName != "rootMessage") {
                    addStatement("    rootMessageVariableName = %S", element.rootMessageVariableName)
                }
                if (element.counterVariableName != "counter") {
                    addStatement("    counterVariableName = %S", element.counterVariableName)
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

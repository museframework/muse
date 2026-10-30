package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for UntilSuccessfulConfig.
 *
 * Generates DSL code like:
 * ```
 * untilSuccessful {
 *     maxRetries = 5
 *     millisBetweenRetries = 1000
 *     ... nested pipeline elements ...
 * }
 * ```
 */
object KotlinPoetUntilSuccessfulCodeEmitter : ElementCodeEmitter<UntilSuccessfulConfig> {

    override fun emit(element: UntilSuccessfulConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.untilSuccessful")

        val codeBlock = CodeBlock.builder()
            .addStatement("untilSuccessful {")
            .apply {
                if (element.maxRetries != 5) {
                    addStatement("    maxRetries = %L", element.maxRetries)
                }
                if (element.millisBetweenRetries != 1000L) {
                    addStatement("    millisBetweenRetries = %L", element.millisBetweenRetries)
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

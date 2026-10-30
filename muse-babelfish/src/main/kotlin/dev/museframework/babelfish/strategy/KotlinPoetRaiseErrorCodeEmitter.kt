package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.RaiseErrorConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for RaiseErrorConfig.
 *
 * Generates DSL code like:
 * ```
 * raiseError {
 *     type = "MULE:EXPRESSION"
 *     description = "Intentional failure"
 * }
 * ```
 */
object KotlinPoetRaiseErrorCodeEmitter : ElementCodeEmitter<RaiseErrorConfig> {

    override fun emit(element: RaiseErrorConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.raiseError")

        val codeBlock = CodeBlock.builder()
            .addStatement("raiseError {")
            .addStatement("    type = %S", element.type)
            .apply {
                if (element.description.isNotEmpty()) {
                    addStatement("    description = %S", element.description)
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }
}

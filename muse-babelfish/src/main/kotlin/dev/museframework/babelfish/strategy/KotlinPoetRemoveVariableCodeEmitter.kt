package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.RemoveVariableConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for RemoveVariableConfig.
 *
 * Generates DSL code like:
 * ```
 * removeVariable("variableName") {
 *     docName = "..."  // optional
 * }
 * ```
 */
object KotlinPoetRemoveVariableCodeEmitter : ElementCodeEmitter<RemoveVariableConfig> {
    override fun emit(element: RemoveVariableConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.removeVariable")

        val codeBlock = CodeBlock.builder()
            .addStatement("removeVariable(%S) {", element.variableName)
            .indent()
            .apply {
                if (element.docName.isNotBlank()) {
                    addStatement("docName = %S", element.docName)
                }
            }
            .unindent()
            .add("}")
            .build()

        return codeBlock.toString()
    }
}

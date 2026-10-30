package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.SetVariableConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for SetVariableConfig.
 *
 * Generates DSL code like:
 * ```
 * setVariable("variableName") {
 *     value = "..."
 *     mimeType = "..."  // optional
 * }
 * ```
 */
object KotlinPoetSetVariableCodeEmitter : ElementCodeEmitter<SetVariableConfig> {
    override fun emit(element: SetVariableConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.setVariable")

        val codeBlock = CodeBlock.builder()
            .addStatement("setVariable(%S) {", element.variableName)
            .indent()
            .addStatement("value = %S", element.value)
            .apply {
                element.mimeType?.let { mimeType ->
                    addStatement("mimeType = %S", mimeType)
                }
            }
            .unindent()
            .add("}")
            .build()

        return codeBlock.toString()
    }
}

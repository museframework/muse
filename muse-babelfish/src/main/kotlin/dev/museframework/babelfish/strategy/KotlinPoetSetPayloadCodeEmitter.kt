package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.SetPayloadConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for SetPayloadConfig.
 *
 * Generates DSL code like:
 * ```
 * setPayload {
 *     value = "Hello World"
 *     mimeType = "text/plain"
 *     encoding = "UTF-8"
 * }
 * ```
 */
object KotlinPoetSetPayloadCodeEmitter : ElementCodeEmitter<SetPayloadConfig> {

    override fun emit(element: SetPayloadConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.setPayload")

        val codeBlock = CodeBlock.builder()
            .addStatement("setPayload {")
            .addStatement("    value = %S", element.value)
            .apply {
                element.mimeType?.let { mimeType ->
                    addStatement("    mimeType = %S", mimeType)
                }
                element.encoding?.let { encoding ->
                    addStatement("    encoding = %S", encoding)
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }
}

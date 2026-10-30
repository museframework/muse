package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for ApikitRouter.
 *
 * Generates DSL code like:
 * ```
 * apikit("config-name") {
 *     docName = "..."
 *     docId = "..."
 * }
 * ```
 */
object KotlinPoetApikitRouterCodeEmitter : ElementCodeEmitter<ApikitRouter> {

    override fun emit(element: ApikitRouter, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.apikit.api.dsl.apikit")

        val refValue = when (val ref = element.configRef) {
            is dev.museframework.apikit.api.config.ApikitRef.ByName -> ref.name
            is dev.museframework.apikit.api.config.ApikitRef.ByReference -> ref.config.name
        }

        val codeBlock = CodeBlock.builder()
            .addStatement("apikit(%S) {", refValue)
            .apply {
                if (element.docName.isNotBlank()) {
                    addStatement("    docName = %S", element.docName)
                }
            }
            .addStatement("    docId = %S", element.docId)
            .add("}")
            .build()

        return codeBlock.toString()
    }
}

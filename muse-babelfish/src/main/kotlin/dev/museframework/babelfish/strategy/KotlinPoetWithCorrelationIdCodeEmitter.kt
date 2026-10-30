package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for WithCorrelationIdConfig.
 *
 * Generates DSL code like:
 * ```
 * withCorrelationId {
 *     correlationId = "#[vars.correlationId]"
 *     ... nested pipeline elements ...
 * }
 * ```
 */
object KotlinPoetWithCorrelationIdCodeEmitter : ElementCodeEmitter<WithCorrelationIdConfig> {

    override fun emit(element: WithCorrelationIdConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.withCorrelationId")

        val codeBlock = CodeBlock.builder()
            .addStatement("withCorrelationId {")
            .apply {
                addStatement("    correlationId = %S", element.correlationId)

                if (element.docName.isNotBlank()) {
                    addStatement("    docName = %S", element.docName)
                }

                if (element.docId.isNotBlank()) {
                    addStatement("    docId = %S", element.docId)
                }

                element.docDescription?.let { desc ->
                    addStatement("    docDescription = %S", desc)
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

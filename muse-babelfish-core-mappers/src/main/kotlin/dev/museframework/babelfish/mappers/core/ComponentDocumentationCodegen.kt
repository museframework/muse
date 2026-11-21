package dev.museframework.babelfish.mappers.core

import com.squareup.kotlinpoet.CodeBlock
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.dsl.api.model.component.MuseComponent

internal fun CodeBlock.Builder.addDocumentation(component: MuseComponent, ctx: CodegenContext) {
    addDocumentationId(component.docId, ctx)
    if (component.docName.isNotBlank()) {
        add("    docName·=·\"%L\"\n", ctx.escapeKotlinString(component.docName))
    }
}

internal fun CodeBlock.Builder.addDocumentationId(docId: String, ctx: CodegenContext) {
    // Escaped literals preserve CRLF when the generated block is indented.
    add("    docId·=·\"%L\"\n", ctx.escapeKotlinString(docId))
}

package dev.museframework.babelfish.mappers.core

import com.squareup.kotlinpoet.CodeBlock
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.dsl.api.model.component.MuseComponent

internal fun CodeBlock.Builder.addDocumentation(component: MuseComponent) {
    addStatement("docId = %L", kotlinStringLiteral(component.docId))
    if (component.docName.isNotEmpty()) {
        addStatement("docName = %L", kotlinStringLiteral(component.docName))
    }
}

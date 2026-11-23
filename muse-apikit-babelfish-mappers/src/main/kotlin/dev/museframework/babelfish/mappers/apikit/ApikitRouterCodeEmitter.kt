package dev.museframework.babelfish.mappers.apikit

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral

object ApikitRouterCodeEmitter : ElementCodeEmitter<ApikitRouter> {
    private val apikit = MemberName("dev.museframework.apikit.api.dsl", "apikit", isExtension = true)

    override fun emit(element: ApikitRouter, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        val name = when (val ref = element.configRef) {
            is ApikitRef.ByName -> ref.name
            is ApikitRef.ByReference -> ref.config.name
        }
        add("%M(%L) {\n", apikit, kotlinStringLiteral(name))
        indent()
        if (element.docName.isNotBlank()) addStatement("docName = %L", kotlinStringLiteral(element.docName))
        addStatement("docId = %L", kotlinStringLiteral(element.docId))
        unindent()
        add("}")
    }
}

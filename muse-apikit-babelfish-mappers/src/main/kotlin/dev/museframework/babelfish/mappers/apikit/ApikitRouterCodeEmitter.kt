package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter

object ApikitRouterCodeEmitter : ElementCodeEmitter<ApikitRouter> {
    override fun emit(
        element: ApikitRouter,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.apikit.api.dsl.apikit")

        val refValue = when (val ref = element.configRef) {
            is dev.museframework.apikit.api.config.ApikitRef.ByName -> ref.name
            is dev.museframework.apikit.api.config.ApikitRef.ByReference -> ref.config.name
        }

        return buildString {
            appendLine("""apikit("$refValue") {""")
            if (element.docName.isNotBlank()) {
                appendLine("""    docName = "${element.docName}"""".trimIndent())
            }
            appendLine("""    docId = "${element.docId}"""".trimIndent())
            append("}")
        }
    }
}

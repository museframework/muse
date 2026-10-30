package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression
import com.squareup.kotlinpoet.CodeBlock

object TransformCodeEmitter : ElementCodeEmitter<TransformConfig> {
    override fun emit(
        element: TransformConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.dsl.dsl.transform")
        ctx.addImport("dev.museframework.dsl.api.expression.DwExpression")

        val codeBlock = CodeBlock.builder().apply {
            add("transform·{\n")
            add("    docName·=·%S\n", element.docName)
            add("    docId·=·%S\n", element.docId)

            element.setPayload?.let { dwExpr ->
                add("    setPayload·=·%L\n", emitDwExpression(dwExpr))
            }

            element.setAttributes?.let { dwExpr ->
                add("    setAttributes·=·%L\n", emitDwExpression(dwExpr))
            }

            if (element.setVariables.isNotEmpty()) {
                element.setVariables.forEach { variable ->
                    val varName = variable.variableName
                    val varExpr = emitDwExpression(variable.expression)
                    add("    setVariable(%S,·%L)\n", varName, varExpr)
                }
            }

            add("}")
        }.build()

        return codeBlock.toString()
    }

    private fun emitDwExpression(expr: DwExpression): CodeBlock =
        when (expr) {
            is DwExpression.Inline -> {
                val escapedContent = expr.expression.replace("\"\"\"", "\\\"\\\"\\\"")
                CodeBlock.of("DwExpression.Inline(\"\"\"%L\"\"\")", escapedContent)
            }

            is DwExpression.Resource -> {
                CodeBlock.of("DwExpression.Resource(%S)", expr.path)
            }
        }
}

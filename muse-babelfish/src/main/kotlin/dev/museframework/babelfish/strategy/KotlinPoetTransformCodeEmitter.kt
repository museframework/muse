package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression
import com.squareup.kotlinpoet.CodeBlock

object KotlinPoetTransformCodeEmitter : ElementCodeEmitter<TransformConfig> {

    override fun emit(element: TransformConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.transform")
        ctx.addImport("dev.museframework.dsl.api.expression.DwExpression")
        ctx.addImport("dev.museframework.dsl.api.config.SetVariableTransformConfig")

        val codeBlock = CodeBlock.builder()
            .add("transform {\n")
            .add("    docName = %S\n", element.docName)
            .add("    docId = %S\n", element.docId)
            .apply {
                element.setPayload?.let { dwExpr ->
                    add("    setPayload = %L\n", emitDwExpression(dwExpr))
                }
                element.setAttributes?.let { dwExpr ->
                    add("    setAttributes = %L\n", emitDwExpression(dwExpr))
                }
                element.setVariables.forEach { variable ->
                    add("    setVariable(%S, %L)\n", variable.variableName, emitDwExpression(variable.expression))
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }

    private fun emitDwExpression(expr: DwExpression): String = when (expr) {
        is DwExpression.Inline -> {
            val escapedContent = expr.expression.replace("\"\"\"", "\\\"\\\"\\\"")
            "DwExpression.Inline(\"\"\"" + escapedContent + "\"\"\")"
        }
        is DwExpression.Resource -> "DwExpression.Resource(\"${expr.path}\")"
    }
}
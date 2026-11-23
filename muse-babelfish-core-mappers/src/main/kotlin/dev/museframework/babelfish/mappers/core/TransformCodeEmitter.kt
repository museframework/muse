package dev.museframework.babelfish.mappers.core

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression

object TransformCodeEmitter : ElementCodeEmitter<TransformConfig> {
    private val transform = MemberName("dev.museframework.dsl.dsl", "transform")

    override fun emit(element: TransformConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", transform)
        addDocumentation(element)
        element.setPayload?.let { addStatement("setPayload = %L", emitExpression(it)) }
        element.setAttributes?.let { addStatement("setAttributes = %L", emitExpression(it)) }
        for (variable in element.setVariables) {
            addStatement(
                "setVariable(%L, %L)",
                kotlinStringLiteral(variable.variableName),
                emitExpression(variable.expression),
            )
        }
        endControlFlow()
    }

    private fun emitExpression(expression: DwExpression): CodeBlock = when (expression) {
        is DwExpression.Inline -> CodeBlock.of(
            "%T.Inline(%L)",
            DwExpression::class,
            kotlinStringLiteral(expression.expression),
        )

        is DwExpression.Resource -> CodeBlock.of(
            "%T.Resource(%L)",
            DwExpression::class,
            kotlinStringLiteral(expression.path),
        )
    }
}

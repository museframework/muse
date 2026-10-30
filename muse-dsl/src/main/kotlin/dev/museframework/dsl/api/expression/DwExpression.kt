package dev.museframework.dsl.api.expression

sealed class DwExpression {
    data class Inline(
        val expression: String,
    ) : DwExpression()

    data class Resource(
        val path: String,
    ) : DwExpression()
}

package dev.museframework.dsl.api.config

import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.dsl.api.model.component.Processor

data class SetVariableTransformConfig(
    val variableName: String,
    val expression: DwExpression,
)

data class TransformConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val setPayload: DwExpression? = null,
    val setAttributes: DwExpression? = null,
    val setVariables: List<SetVariableTransformConfig> = emptyList(),
) : Processor

package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.RemoveVariableBuilder

@MuseDsl
fun PipelineOwner.removeVariable(
    variableName: String,
    block: RemoveVariableBuilder.() -> Unit,
) {
    processor(RemoveVariableBuilder(variableName).apply(block).build())
}

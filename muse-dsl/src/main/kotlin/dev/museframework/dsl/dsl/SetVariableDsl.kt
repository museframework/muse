package dev.museframework.dsl.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.builder.SetVariableBuilder

@MuseDsl
fun PipelineOwner.setVariable(
    variableName: String,
    block: SetVariableBuilder.() -> Unit,
) {
    processor(SetVariableBuilder(variableName).apply(block).build())
}

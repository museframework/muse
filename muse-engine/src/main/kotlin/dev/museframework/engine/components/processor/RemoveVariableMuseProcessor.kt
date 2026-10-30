package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.RemoveVariableConfig
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.spi.ProcessorBuilder

class RemoveVariableMuseProcessor(
    override val component: RemoveVariableConfig,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult {
        val newVariables = context.event.variables - component.variableName
        val newEvent = context.event.copy(variables = newVariables)
        return ProcessorResult.Success(context.withEvent(newEvent))
    }
}

val removeVariableProcessorBuilder =
    ProcessorBuilder<RemoveVariableConfig> { component, _ ->
        RemoveVariableMuseProcessor(component)
    }

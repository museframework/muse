package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.streaming.StreamingEventResources
import dev.museframework.engine.api.spi.ProcessorBuilder
import dev.museframework.engine.dataweave.DataWeaveException

class SetVariableMuseProcessor(
    override val component: SetVariableConfig,
    private val expressionEvaluator: MuseExpressionEvaluator,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult =
        try {
            val evaluated =
                expressionEvaluator.evaluate(
                    component.value,
                    context.event,
                )

            val result =
                if (component.mimeType != null && evaluated != null) {
                    evaluated.withMimeType(component.mimeType!!)
                } else {
                    evaluated
                }

            StreamingEventResources.registerValueProviders(result, context.streamingResources)
            val newVariables = context.event.variables.toMutableMap()
            newVariables[component.variableName] = result

            val newEvent = context.event.copy(variables = newVariables)
            ProcessorResult.Success(context.withEvent(newEvent))
        } catch (e: DataWeaveException) {
            ProcessorResult.Failure(
                context = context,
                error =
                    MuseError(
                        errorType = ErrorType.Mule.EXPRESSION,
                        description = "Expression evaluation failed: ${e.message}",
                        detailedDescription = e.cause?.message ?: e.message ?: "Unknown error",
                        cause = e,
                        sourceFile = component.sourceFile,
                        sourceLine = component.sourceLine,
                        sourceClass = component.sourceClass,
                    ),
            )
        }
}

val setVariableProcessorBuilder =
    ProcessorBuilder<SetVariableConfig> { component, context ->
        SetVariableMuseProcessor(component, context.expressionEvaluator)
    }

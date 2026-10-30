package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.spi.ProcessorBuilder
import dev.museframework.engine.dataweave.*

class TransformMuseProcessor(
    override val component: TransformConfig,
    private val dataWeaveExecutor: DataWeaveExecutor,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult =
        try {
            val dwContext = DataWeaveContext(context.event)

            val newPayload =
                component.setPayload?.let {
                    dataWeaveExecutor.evaluateDwExpression(it, dwContext)
                } ?: context.event.payload

            val newAttributes =
                component.setAttributes?.let {
                    dataWeaveExecutor.evaluateDwExpression(it, dwContext)
                } ?: context.event.attributes

            val newVariables = context.event.variables.toMutableMap()
            component.setVariables.forEach { setVar ->
                val value = dataWeaveExecutor.evaluateDwExpression(setVar.expression, dwContext)
                newVariables[setVar.variableName] = value
            }

            val newEvent =
                context.event.copy(
                    payload = newPayload,
                    attributes = newAttributes,
                    variables = newVariables,
                )

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

val transformProcessorBuilder =
    ProcessorBuilder<TransformConfig> { component, _ ->
        TransformMuseProcessor(component, MuseDataWeaveExecutor())
    }

package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.CustomCodeConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.spi.ProcessorBuilder

class CustomCodeMuseProcessor(
    override val component: CustomCodeConfig,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult =
        try {
            val payload = context.event.payload?.value
            val result = component.code(payload)

            val newEvent =
                if (result != null) {
                    context.event.copy(payload = TypedValue.of(result))
                } else {
                    context.event.copy(payload = null)
                }

            ProcessorResult.Success(context.withEvent(newEvent))
        } catch (e: Exception) {
            if (component.handleErrors) {
                val error =
                    MuseError(
                        errorType = ErrorType.Mule.UNKNOWN,
                        description = "Custom code execution failed: ${e.message}",
                        detailedDescription = e.stackTraceToString(),
                        cause = e,
                        sourceFile = component.sourceFile,
                        sourceLine = component.sourceLine,
                        sourceClass = component.sourceClass,
                    )
                ProcessorResult.Failure(error, context.withError(error))
            } else {
                throw e
            }
        }
}

val customCodeProcessorBuilder =
    ProcessorBuilder<CustomCodeConfig> { component, _ ->
        CustomCodeMuseProcessor(component)
    }

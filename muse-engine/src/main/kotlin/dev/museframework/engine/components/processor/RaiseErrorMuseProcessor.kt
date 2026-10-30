package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.spi.ProcessorBuilder

class RaiseErrorMuseProcessor(
    override val component: RaiseErrorConfig,
    private val expressionEvaluator: MuseExpressionEvaluator,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult {
        val description =
            expressionEvaluator
                .evaluate(
                    component.description,
                    context.event,
                )?.value
                ?.toString() ?: component.description

        val errorType = resolveErrorType(component.type)

        return ProcessorResult.Failure(
            error =
                MuseError(
                    description = description,
                    detailedDescription = description,
                    errorType = errorType,
                    cause = RuntimeException(description),
                ),
            context = context,
        )
    }

    companion object {
        private val knownMuleTypes: Map<String, ErrorType> =
            buildMap {
                fun register(type: ErrorType) {
                    put(type.identifier, type)
                }
                register(ErrorType.Mule.ANY)
                register(ErrorType.Mule.TRANSFORMATION)
                register(ErrorType.Mule.EXPRESSION)
                register(ErrorType.Mule.VALIDATION)
                register(ErrorType.Mule.DUPLICATE_MESSAGE)
                register(ErrorType.Mule.REDELIVERY_EXHAUSTED)
                register(ErrorType.Mule.CONNECTIVITY)
                register(ErrorType.Mule.RETRY_EXHAUSTED)
                register(ErrorType.Mule.ROUTING)
                register(ErrorType.Mule.COMPOSITE_ROUTING)
                register(ErrorType.Mule.SECURITY)
                register(ErrorType.Mule.CLIENT_SECURITY)
                register(ErrorType.Mule.SERVER_SECURITY)
                register(ErrorType.Mule.NOT_PERMITTED)
                register(ErrorType.Mule.STREAM_MAXIMUM_SIZE_EXCEEDED)
                register(ErrorType.Mule.TIMEOUT)
                register(ErrorType.Mule.UNKNOWN)
                register(ErrorType.Mule.CRITICAL)
                register(ErrorType.Mule.OVERLOAD)
                register(ErrorType.Mule.FLOW_BACK_PRESSURE)
                register(ErrorType.Mule.FATAL_JVM_ERROR)
            }

        fun resolveErrorType(type: String): ErrorType {
            val parts = type.split(":")
            val namespace = if (parts.size == 2) parts[0] else "MULE"
            val identifier = if (parts.size == 2) parts[1] else type

            if (namespace == "MULE") {
                knownMuleTypes[identifier]?.let { return it }
            }

            return ErrorType(
                namespace = namespace,
                identifier = identifier,
                parent = ErrorType.Mule.ANY,
            )
        }
    }
}

val raiseErrorProcessorBuilder =
    ProcessorBuilder<RaiseErrorConfig> { component, context ->
        RaiseErrorMuseProcessor(component, context.expressionEvaluator)
    }

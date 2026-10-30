package dev.museframework.engine.components.processor

import dev.museframework.dsl.api.config.LogLevel
import dev.museframework.dsl.api.config.LoggerConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.spi.ProcessorBuilder
import org.slf4j.LoggerFactory

class LoggerMuseProcessor(
    override val component: LoggerConfig,
    private val expressionEvaluator: MuseExpressionEvaluator,
) : MuseProcessor {
    private val logger = LoggerFactory.getLogger(LoggerMuseProcessor::class.java)

    override suspend fun process(context: ExecutionContext): ProcessorResult {
        try {
            val message =
                expressionEvaluator
                    .evaluate(component.message, context.event)
                    ?.getOrNull()
                    ?.toString()
                    ?: ""

            when (component.level) {
                LogLevel.TRACE -> logger.trace(message)
                LogLevel.DEBUG -> logger.debug(message)
                LogLevel.INFO -> logger.info(message)
                LogLevel.WARN -> logger.warn(message)
                LogLevel.ERROR -> logger.error(message)
            }

            return ProcessorResult.Success(context)
        } catch (e: Exception) {
            val error = MuseError(
                description = "Logger expression evaluation failed",
                detailedDescription = e.message ?: "Unknown error",
                errorType = ErrorType.Mule.EXPRESSION,
                cause = e,
            )
            return ProcessorResult.Failure(
                error = error,
                context = context.withError(error),
            )
        }
    }
}

val loggerProcessorBuilder =
    ProcessorBuilder<LoggerConfig> { component, context ->
        LoggerMuseProcessor(component, context.expressionEvaluator)
    }

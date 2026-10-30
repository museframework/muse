package dev.museframework.babelfish.mapper

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.error.ErrorType

/**
 * Parses MuleSoft error type strings like "MULE:EXPRESSION" or "HTTP:BAD_REQUEST"
 * into ErrorType objects.
 */
object ErrorTypeParser {
    fun parse(typeString: String): ErrorType {
        val parts = typeString.split(":", limit = 2)
        return if (parts.size == 2) {
            ErrorType(
                namespace = parts[0].uppercase(),
                identifier = parts[1].uppercase(),
            )
        } else {
            // Fallback: treat as MULE namespace
            ErrorType(
                namespace = "MULE",
                identifier = typeString.uppercase(),
            )
        }
    }
}

/**
 * Maps Mule XML <error-handler> elements to ErrorHandler.
 *
 * <error-handler>
 *   <on-error-continue type="MULE:EXPRESSION" whenExpression="#[payload contains 'retry']">
 *     <logger level="ERROR" message="Expression error"/>
 *   </on-error-continue>
 *   <on-error-propagate type="HTTP:BAD_REQUEST">
 *     <set-payload value='"Bad Request"'/>
 *   </on-error-propagate>
 * </error-handler>
 */
object ErrorHandlerMapper {
    fun map(
        element: RawElement,
        ctx: MappingContext,
    ): ErrorHandler {
        val strategies =
            element.children.map { strategyEl ->
                mapStrategy(strategyEl, ctx)
            }
        return ErrorHandler(strategies = strategies)
    }

    private fun mapStrategy(
        element: RawElement,
        ctx: MappingContext,
    ): ErrorHandlerStrategy {
        val errorType =
            element.attr("type")?.let { ErrorTypeParser.parse(it) }
                ?: ErrorType.Mule.ANY
        val pipeline = element.children.map { child -> ctx.mapPipelineElement(child) }
        val whenExpression = element.attr("whenExpression")
        val enableNotifications = element.attr("enableNotifications")?.toBooleanStrictOrNull() ?: true
        val logException = element.attr("logException")?.toBooleanStrictOrNull() ?: true

        return when (element.tag) {
            "on-error-continue" -> {
                ErrorHandlerStrategy.OnErrorContinue(
                    errorType = errorType,
                    pipeline = pipeline,
                    whenExpression = whenExpression,
                    enableNotifications = enableNotifications,
                    logException = logException,
                )
            }

            "on-error-propagate" -> {
                ErrorHandlerStrategy.OnErrorPropagate(
                    errorType = errorType,
                    pipeline = pipeline,
                    whenExpression = whenExpression,
                    enableNotifications = enableNotifications,
                    logException = logException,
                )
            }

            else -> {
                error("Unknown error handler strategy: ${element.tag}")
            }
        }
    }
}

/**
 * Emits Kotlin DSL code for ErrorHandlerStrategy instances.
 */
object ErrorHandlerStrategyCodeEmitter {
    fun emitStrategy(
        strategy: ErrorHandlerStrategy,
        ctx: CodegenContext,
    ): String =
        when (strategy) {
            is ErrorHandlerStrategy.OnErrorContinue -> emitStrategyCall("onErrorContinue", strategy, ctx)
            is ErrorHandlerStrategy.OnErrorPropagate -> emitStrategyCall("onErrorPropagate", strategy, ctx)
        }

    private fun emitStrategyCall(
        functionName: String,
        strategy: ErrorHandlerStrategy,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.dsl.api.model.error.ErrorType")

        val hasConfiguration =
            strategy.whenExpression != null ||
                !strategy.enableNotifications ||
                !strategy.logException
        val namespace = ctx.escapeKotlinString(strategy.errorType.namespace)
        val identifier = ctx.escapeKotlinString(strategy.errorType.identifier)

        return buildString {
            if (hasConfiguration) {
                appendLine("$functionName(")
                appendLine("    ErrorType(\"$namespace\", \"$identifier\"),")
                appendLine("    configure = {")
                strategy.whenExpression?.let { whenExpression ->
                    appendLine("        whenExpression = \"${ctx.escapeKotlinString(whenExpression)}\"")
                }
                if (!strategy.enableNotifications) {
                    appendLine("        enableNotifications = false")
                }
                if (!strategy.logException) {
                    appendLine("        logException = false")
                }
                appendLine("    },")
                append(") {")
            } else {
                append("$functionName(ErrorType(\"$namespace\", \"$identifier\")) {")
            }

            if (strategy.pipeline.isNotEmpty()) {
                appendLine()
                append(ctx.emitPipelineElements(strategy.pipeline, "    "))
            }
            append("}")
        }
    }
}

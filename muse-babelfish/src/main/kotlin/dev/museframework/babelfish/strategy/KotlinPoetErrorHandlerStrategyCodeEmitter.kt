package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.error.ErrorType
import com.squareup.kotlinpoet.CodeBlock

object KotlinPoetErrorHandlerStrategyCodeEmitter {
    fun emitStrategy(strategy: ErrorHandlerStrategy, ctx: CodegenContext): String {
        return when (strategy) {
            is ErrorHandlerStrategy.OnErrorContinue -> emitOnErrorContinue(strategy, ctx)
            is ErrorHandlerStrategy.OnErrorPropagate -> emitOnErrorPropagate(strategy, ctx)
        }
    }

    private fun emitOnErrorContinue(
        strategy: ErrorHandlerStrategy.OnErrorContinue,
        ctx: CodegenContext,
    ): String = emitStrategyCall("onErrorContinue", strategy, ctx)

    private fun emitOnErrorPropagate(
        strategy: ErrorHandlerStrategy.OnErrorPropagate,
        ctx: CodegenContext,
    ): String = emitStrategyCall("onErrorPropagate", strategy, ctx)

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

        return CodeBlock.builder()
            .apply {
                if (hasConfiguration) {
                    add("%L(\n", functionName)
                    add("    ErrorType(%S, %S),\n", strategy.errorType.namespace, strategy.errorType.identifier)
                    add("    configure = {\n")
                    strategy.whenExpression?.let { whenExpression ->
                        add("        whenExpression = %S\n", whenExpression)
                    }
                    if (!strategy.enableNotifications) {
                        add("        enableNotifications = false\n")
                    }
                    if (!strategy.logException) {
                        add("        logException = false\n")
                    }
                    add("    },\n")
                    add(") {\n")
                } else {
                    add("%L(ErrorType(%S, %S)) {\n", functionName, strategy.errorType.namespace, strategy.errorType.identifier)
                }
                if (strategy.pipeline.isNotEmpty()) {
                    add(ctx.emitPipelineElements(strategy.pipeline, "    "))
                }
            }
            .add("}")
            .build()
            .toString()
    }
}

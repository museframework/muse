package dev.museframework.engine.execution

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.Pipeline
import dev.museframework.engine.api.execution.PipelineElement
import dev.museframework.engine.api.execution.PipelineScope
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.ResolvedErrorHandler
import dev.museframework.engine.api.execution.ResolvedErrorHandlerStrategy
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.integration.CaptureConfig
import dev.museframework.engine.integration.CaptureMuseProcessor
import kotlinx.coroutines.CoroutineScope

internal fun pipelineOf(vararg processors: MuseProcessor): Pipeline =
    Pipeline(processors.map(::PipelineElement))

internal fun probe(
    id: String,
    action: suspend (ExecutionContext) -> ProcessorResult,
): MuseProcessor = CaptureMuseProcessor(CaptureConfig(id, id), action)

internal fun policyError(name: String): MuseError =
    MuseError(
        description = name,
        detailedDescription = name,
        errorType = ErrorType("APP", name, ErrorType.Mule.ANY),
        cause = IllegalStateException(name),
    )

internal fun policyContext(
    scope: CoroutineScope,
    event: MuseEvent = MuseEvent(),
    handler: ResolvedErrorHandler? = null,
): ExecutionContext =
    ExecutionContext(
        event = event,
        errorHandler = handler,
        coroutineScope = scope,
        currentScope =
            PipelineScope.FlowScope(
                MuseFlow.Flow(name = "policy-test", docId = "policy-test"),
            ),
        expressionEvaluator =
            object : MuseExpressionEvaluator {
                override fun evaluate(
                    expression: String,
                    event: MuseEvent,
                ): TypedValue<Any>? =
                    if (expression == "#[true]") TypedValue.of(true as Any) else null
            },
    )

internal fun continueHandler(pipeline: Pipeline): ResolvedErrorHandler =
    ResolvedErrorHandler(
        listOf(
            ResolvedErrorHandlerStrategy(
                errorType = ErrorType.Mule.ANY,
                pipeline = pipeline,
                kind = ResolvedErrorHandlerStrategy.Kind.CONTINUE,
            ),
        ),
    )

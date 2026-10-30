package dev.museframework.engine.execution

import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.Pipeline
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.streaming.StreamingEventResources

/**
 * Canonical raw traversal: runs a pipeline to one terminal result without consulting any error
 * handler. Owners ([OwnedExecution]) and prepared policy bodies are the only dispatch paths.
 */
internal suspend fun ExecutionContext.runUnhandled(pipeline: Pipeline): ProcessorResult =
    withCorrelationIdMdc(event.correlationId) {
        StreamingEventResources.registerEventProviders(event, streamingResources)

        var current = this@runUnhandled
        for ((index, element) in pipeline.elements.withIndex()) {
            val entry = current
            val result =
                entry
                    .pushFrame(element.processor.component, index)
                    .invokeTerminal(element.processor)
                    .mapTerminalContext { it.restoreExecutionMetadataFrom(entry) }

            if (result is ProcessorResult.Failure) {
                return@withCorrelationIdMdc result
            }
            current = result.terminalContext()
        }

        ProcessorResult.Success(current)
    }

private suspend fun ExecutionContext.invokeTerminal(processor: MuseProcessor): ProcessorResult {
    val ambientError = event.error

    val result = processor.process(this)
    StreamingEventResources.registerEventProviders(result.terminalContext().event, streamingResources)

    val normalized =
        when (result) {
            is ProcessorResult.Success ->
                ProcessorResult.Success(
                    result.context.withEvent(result.context.event.copy(error = ambientError)),
                )

            is ProcessorResult.Failure ->
                ProcessorResult.Failure(
                    error = result.error,
                    context = result.context.withEvent(result.context.event.copy(error = result.error)),
                )
        }

    return normalized.mapTerminalContext { it.restoreExecutionMetadataFrom(this) }
}

internal fun ProcessorResult.terminalContext(): ExecutionContext =
    when (this) {
        is ProcessorResult.Success -> context
        is ProcessorResult.Failure -> context
    }

internal fun ProcessorResult.mapTerminalContext(
    transform: (ExecutionContext) -> ExecutionContext,
): ProcessorResult =
    when (this) {
        is ProcessorResult.Success -> copy(context = transform(context))
        is ProcessorResult.Failure -> copy(context = transform(context))
    }

private fun ExecutionContext.restoreExecutionMetadataFrom(entry: ExecutionContext): ExecutionContext =
    copy(
        callStack = entry.callStack,
        coroutineScope = entry.coroutineScope,
        streamingResources = entry.streamingResources,
        currentScope = entry.currentScope,
    )

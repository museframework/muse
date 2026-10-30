package dev.museframework.engine.execution

import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult

internal fun interface BoundExecution {
    suspend fun invoke(context: ExecutionContext): ProcessorResult
}

# ADR-001 — Execution Model: Result Type, Not Exceptions

## Status
Accepted

## Context
The Muse engine executes integration flows using Kotlin Coroutines as the concurrency model. Processors transform events and routers control flow execution. We needed a consistent mechanism for:
- Control flow branching (sequential vs parallel execution)
- Error propagation without breaking coroutine structure
- Return of multiple results from scatter-gather patterns
- Iteration over collections with both sequential and parallel variants

## Decision
`MuseProcessor` returns `ProcessorResult` (sealed class), never throws. The executor pattern-matches on the result and dispatches to specialized handlers.

```kotlin
sealed class ProcessorResult {
    data class Success(val context: ExecutionContext) : ProcessorResult()
    data class Failure(val error: MuseError, val context: ExecutionContext) : ProcessorResult()
    data class SuccessWithPipeline(
        val context: ExecutionContext,
        val pipeline: Pipeline,
        val targetConfig: TargetConfig? = null
    ) : ProcessorResult()  // used by routers (flowRef, choice)
    data class SuccessWithParallelPipelines(
        val context: ExecutionContext,
        val pipelines: List<Pipeline>,
        val aggregator: ParallelAggregator,
        val timeoutMs: Long? = null,
        val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY
    ) : ProcessorResult()  // scatter-gather
    data class SuccessWithIteration(
        val context: ExecutionContext,
        val pipeline: Pipeline,
        val items: List<Any?>,
        val batchSize: Int? = null,
        val targetConfig: TargetConfig? = null
    ) : ProcessorResult()  // foreach sequential
    data class SuccessWithParallelIteration(
        val context: ExecutionContext,
        val pipeline: Pipeline,
        val items: List<Any?>,
        val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
        val timeoutMs: Long? = null,
        val targetConfig: TargetConfig? = null
    ) : ProcessorResult()  // foreach parallel
}
```

## Consequences

**Positive:**
- Full control over branching, error handling, and concurrency
- No exception handling overhead in coroutine dispatch loops
- Type-safe pattern matching forces handling of all result types
- Clean separation between processor logic and execution orchestration

**Negative:**
- More verbose than throwing exceptions for simple cases
- Requires discipline to never throw from processors
- Additional result types needed for new execution patterns

## Implementation Details

### Processor Interface
```kotlin
fun interface MuseProcessor {
    suspend fun process(context: ExecutionContext): ProcessorResult
}
```

### Handler Dispatch
The `PipelineExecutor` uses pattern matching:
```kotlin
when (result) {
    is Success -> continueWithNextProcessor(result.context)
    is Failure -> handleError(result.error, result.context)
    is SuccessWithPipeline -> executeNestedPipeline(result)
    is SuccessWithParallelPipelines -> parallelPipelinesHandler.handle(result)
    is SuccessWithIteration -> iterationHandler.handle(result)
    is SuccessWithParallelIteration -> parallelIterationHandler.handle(result)
}
```

## References
- `muse-engine/src/main/kotlin/dev/museframework/engine/execution/ProcessorResult.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/execution/MuseProcessor.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/execution/PipelineExecutor.kt`

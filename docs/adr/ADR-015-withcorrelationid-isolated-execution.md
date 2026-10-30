# ADR-015 — WithCorrelationIdMuseProcessor: Isolated Execution

## Status
Accepted

## Context
The `withCorrelationId` scope allows temporarily changing the correlation ID for a section of the pipeline. We needed to decide how errors within this scope should be handled.

## Decision
`WithCorrelationIdMuseProcessor` executes its inner pipeline with `errorHandler = null` and `parent = null`.
This means:

- Errors inside the scope are **not** intercepted by the outer flow's error handler during execution.
- After `execute()` returns, if `result.event.error != null` the processor intercepts the error,
  restores the original correlation ID, and returns `ProcessorResult.Failure` to the caller.
- The caller's `PipelineExecutor` then invokes `handleError` with the original correlation ID in context.

## Consequences

**Positive:**
- Correlation ID changes are isolated to the scope
- Errors are still handled, but with original correlation ID
- Scope behaves like a transaction boundary

**Negative:**
- Complex error handling behavior
- Must understand the two-phase error handling

## Implementation Details

### Effect on Error Handlers

| Error location | Error handler used | correlationId seen by handler |
|---|---|---|
| Inside scope, no inner handler | Outer flow error handler | Original (pre-scope) |
| Inside flow ref with own error handler, called from within scope | Flow ref's own error handler | Scope's correlationId |

### Implementation
```kotlin
class WithCorrelationIdMuseProcessor(
    private val config: WithCorrelationIdConfig,
    private val evaluator: MuseExpressionEvaluator
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult {
        // Evaluate new correlation ID expression
        val newCorrelationId = evaluator.evaluate(
            config.correlationId,
            context
        ).value as String
        
        // Create isolated context
        val isolatedContext = context.childContext(
            event = context.event.copy(correlationId = newCorrelationId),
            errorHandler = null,  // ← no error handler
            parent = null        // ← no parent
        )
        
        // Execute inner pipeline
        val result = pipelineExecutor.execute(isolatedContext, innerPipeline)
        
        // Check for error and restore original correlation ID
        return if (result.event.error != null) {
            ProcessorResult.Failure(
                error = result.event.error,
                context = result.context.copy(
                    event = result.event.copy(
                        correlationId = context.event.correlationId  // ← restored
                    )
                )
            )
        } else {
            ProcessorResult.Success(
                context = result.context.copy(
                    event = result.event.copy(
                        correlationId = context.event.correlationId  // ← restored
                    )
                )
            )
        }
    }
}
```

### DSL Usage
```kotlin
withCorrelationId {
    correlationId = "#[correlationId ++ '-SCOPE']"
    
    // Inner pipeline executes with new correlation ID
    logger { message = "#[correlationId]: Inside scope" }
    
    // If error occurs here:
    // 1. It's caught after inner pipeline completes
    // 2. Original correlation ID is restored
    // 3. Failure result is returned to outer executor
    // 4. Outer flow's error handler sees original correlation ID
}
```

## References
- `muse-engine/src/main/kotlin/dev/museframework/engine/components/router/WithCorrelationIdMuseProcessor.kt`

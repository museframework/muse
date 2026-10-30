# ADR-016 — FlowRefMuseProcessor Carries Referenced Flow Error Handler

## Status
Accepted

## Context
When a flow references another flow via `flowRef`, we needed to decide which error handler should be used for errors in the referenced flow: the caller's or the referenced flow's own.

## Decision
`FlowRefMuseProcessor` resolves the error handler of the referenced `MuseFlow.Flow` at bootstrap
and injects it into the `ExecutionContext` via `SuccessWithPipeline.context`:

```kotlin
context = context.copy(errorHandler = errorHandler ?: context.errorHandler)
```

`MuseFlow.SubFlow` has no error handler — `null` is passed, preserving the caller's error handler.

**Why:** Without this, errors in a referenced flow with its own error handler would propagate
to the caller's error handler instead of being caught locally.

## Consequences

**Positive:**
- Referenced flows can have their own error handling
- Error handling is scoped to the flow that defines it
- SubFlows inherit caller's error handling (appropriate for shared logic)
- Fail-fast validation at bootstrap

**Negative:**
- Slightly complex error handler resolution
- Must understand Flow vs SubFlow distinction

## Implementation Details

### Bootstrap Resolution
```kotlin
class FlowRefMuseProcessor(
    private val config: FlowRefConfig,
    private val referencedFlow: MuseFlow,
    private val errorHandler: ResolvedErrorHandler?,  // ← resolved at bootstrap
    private val evaluator: MuseExpressionEvaluator
) : MuseProcessor {
    // errorHandler is null for SubFlow
    // errorHandler is the flow's handler for Flow
}
```

### Error Handler Injection
```kotlin
override suspend fun process(context: ExecutionContext): ProcessorResult {
    // Create child context with referenced flow's error handler
    val childContext = context.childContext(
        event = context.event,
        errorHandler = errorHandler ?: context.errorHandler  // ← inject here
    )
    
    return ProcessorResult.SuccessWithPipeline(
        context = childContext,
        pipeline = referencedFlow.pipeline,
        targetConfig = config.target
    )
}
```

### Flow vs SubFlow Behavior
```kotlin
// Flow with its own error handler
val orderFlow = flow("order-flow") {
    errorHandler {
        onErrorContinue { ... }
    }
    // ...
}

// SubFlow (no error handler)
val validateOrderSubFlow = subFlow("validate-order") {
    // Uses caller's error handler
    // ...
}

// Usage
flow("main-flow") {
    // Calls order-flow: uses order-flow's error handler
    flowRef(orderFlow) { ... }
    
    // Calls validate-order: uses main-flow's error handler
    flowRef(validateOrderSubFlow) { ... }
}
```

## References
- `muse-engine/src/main/kotlin/dev/museframework/engine/components/router/FlowRefMuseProcessor.kt`
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/api/model/flow/Flow.kt`

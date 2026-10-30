# ADR-004 — MuseExpressionEvaluator in ExecutionContext

## Status
Accepted

## Context
Many handlers (like `applyTargetConfig`) need to evaluate DataWeave expressions. We needed to decide how to make the expression evaluator available throughout the execution pipeline.

## Decision
`ExecutionContext` carries a `MuseExpressionEvaluator` instance.
It is created once per flow in `launchConsumer` and propagated to child contexts via `childContext()`.

## Consequences

**Positive:**
- Expression evaluator available everywhere in execution context
- No need to thread evaluator through every function signature
- Child contexts inherit evaluator automatically
- Consistent evaluation behavior across the pipeline

**Negative:**
- ExecutionContext becomes slightly larger
- Implicit dependency makes testing marginally harder
- Must ensure evaluator is passed in childContext()

## Implementation Details

### ExecutionContext Structure
```kotlin
data class ExecutionContext(
    val event: MuseEvent,
    val errorHandler: ResolvedErrorHandler?,
    val callStack: List<MuseStackFrame>,
    val parent: ExecutionContext?,
    val coroutineScope: CoroutineScope,
    val expressionEvaluator: MuseExpressionEvaluator  // ← here
)
```

### Child Context Creation
```kotlin
fun childContext(
    event: MuseEvent = this.event,
    errorHandler: ResolvedErrorHandler? = this.errorHandler,
    callStack: List<MuseStackFrame> = this.callStack
): ExecutionContext = ExecutionContext(
    event = event,
    errorHandler = errorHandler,
    callStack = callStack,
    parent = this,
    coroutineScope = this.coroutineScope,
    expressionEvaluator = this.expressionEvaluator  // ← propagated
)
```

### Usage Example
```kotlin
// In TargetConfigHandler
fun applyTargetConfig(context: ExecutionContext, targetConfig: TargetConfig) {
    val result = context.expressionEvaluator.evaluate(
        expression = targetConfig.targetValue,
        context = context
    )
    // Store result in target variable
}
```

## References
- `muse-engine/src/main/kotlin/dev/museframework/engine/execution/ExecutionContext.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/expression/MuseExpressionEvaluator.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/execution/TargetConfigHandler.kt`

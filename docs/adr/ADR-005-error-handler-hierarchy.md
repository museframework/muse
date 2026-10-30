# ADR-005 — Error Handler Hierarchy

## Status
Accepted

## Context
Error handling in integration flows can be defined at multiple levels: individual flows, parent contexts for nested flow references, global application level, or a hardcoded default. We needed a clear resolution order.

## Decision
Resolution order for error handling (most specific to most general):
```
flow.errorHandler              (defined on the specific MuseFlow.Flow)
  → parent ExecutionContext errorHandler  (for nested flow references)
    → MuseContext.globalErrorHandler      (application-level, optional)
      → MuseContext.defaultErrorHandler   (hardcoded: on-error-propagate on ANY)
```

### Propagated Try Event State

When a Try `on-error-propagate` strategy finishes with an error, the Try
boundary advances to the enclosing error-handler context without restoring an
event snapshot. The propagated failure combines:

- execution metadata and the enclosing error handler from the Try input
  context;
- the complete result event produced by the inner pipeline and error strategy.

Payload, attributes, and variable mutations therefore remain visible to the
enclosing handler. This event rule is separate from transaction rollback.
`TryConfig.transactionalAction` is currently passive because the engine does
not implement transaction infrastructure.

### Continue Scope Outcome

A successful `on-error-continue` is an explicit recovery outcome, not merely
an event whose error field was cleared. Its strategy executes every processor
while the original error remains available to expressions. A new strategy
failure is never cleared as recovery and must leave the strategy without
selecting that same strategy again.

The pipeline that failed stops at that outcome. Transparent nested pipelines,
including choice branches and SubFlows, forward it. A Try with a local handler
and an owning referenced Flow consume it, compose the resulting event with
their enclosing metadata, and allow the enclosing pipeline to continue after
that scope. A Try without a local handler instead isolates its enclosing
handler while executing its body, then returns the failure to the enclosing
owner; a flow-level handler therefore ends the flow scope rather than resuming
after the Try.

## Consequences

**Positive:**
- Predictable, hierarchical error handling
- Flows can override parent error handling
- Global defaults can be configured at application level
- Hardcoded default ensures no unhandled errors

**Negative:**
- Complex to trace error handling in deeply nested flows
- Must carefully track errorHandler in context
- Scope boundaries must compose result-event state with enclosing execution
  metadata instead of replacing either context wholesale

## Implementation Details

### ErrorHandler Types
```kotlin
// Declarative (model)
data class ErrorHandler(
    val strategies: List<ErrorHandlerStrategy> = emptyList()
)

// Runtime (resolved at bootstrap)
data class ResolvedErrorHandler(
    val strategies: List<ResolvedErrorHandlerStrategy>
)
```

### Error Handler Strategy
```kotlin
sealed class ErrorHandlerStrategy {
    data class OnErrorContinue(
        val errorType: ErrorType,
        val pipeline: List<PipelineElement>,
        val whenExpression: String? = null,
    ) : ErrorHandlerStrategy()

    data class OnErrorPropagate(
        val errorType: ErrorType,
        val pipeline: List<PipelineElement>,
        val whenExpression: String? = null,
    ) : ErrorHandlerStrategy()
}
```

### FlowRef Error-Handling Boundary
```kotlin
val referencedContext =
    context.childContext(
        event = context.event,
        errorHandler = errorHandler ?: ResolvedErrorHandler.EMPTY,
        coroutineScope = context.coroutineScope,
    )

ProcessorResult.SuccessWithPipeline(
    context = referencedContext,
    pipeline = pipeline,
    boundary = PipelineBoundary.REFERENCED_FLOW,
)
```

The child context prevents a caller handler from becoming the referenced
Flow's local handler. The referenced-flow boundary is responsible for
forwarding a remaining failure to its owner.

## References
- `muse-engine-api/src/main/kotlin/dev/museframework/engine/api/execution/ResolvedErrorHandlerStrategy.kt`
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/api/model/error/ErrorHandler.kt`

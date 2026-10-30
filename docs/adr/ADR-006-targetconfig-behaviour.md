# ADR-006 — TargetConfig Behaviour

## Status
Accepted

## Context
When calling sub-flows via `flowRef`, users need a way to capture the result without affecting the calling flow's payload. We defined `TargetConfig` to control this behavior.

## Decision
When a `FlowRefConfig` has a `targetConfig`:
- The sub-flow pipeline executes normally
- The result is **not** merged back into the calling flow's payload
- The result (or `targetValue` expression evaluated on it) is stored in a variable named `targetConfig.target`
- The calling flow's original payload is preserved

`targetValue` is a DataWeave expression evaluated on the sub-flow result event.
If `targetValue` is null, the sub-flow payload is stored directly.

## Consequences

**Positive:**
- Clean separation between sub-flow execution and result capture
- Calling flow state is preserved
- Flexible result extraction via DataWeave expressions
- Works with both sync and async flow references

**Negative:**
- Slightly complex to understand initially
- Must remember that payload is NOT merged when target is set

## Implementation Details

### TargetConfig Model
```kotlin
data class TargetConfig(
    val target: String,           // Variable name to store result
    val targetValue: String? = null  // DataWeave expression (null = use payload)
)
```

### FlowRef with Target
```kotlin
flowRef(mySubFlow) {
    target = TargetConfig(
        target = "subFlowResult",
        targetValue = "#[payload.data.items]"  // Extract specific data
    )
}
```

### Handler Implementation
```kotlin
fun applyTargetConfig(
    context: ExecutionContext,
    subFlowResult: MuseEvent,
    targetConfig: TargetConfig
): MuseEvent {
    // Evaluate targetValue expression or use payload directly
    val value = targetConfig.targetValue?.let { expr ->
        context.expressionEvaluator.evaluate(expr, subFlowResult)
    } ?: subFlowResult.payload
    
    // Store in target variable, preserve original payload
    return context.event.copy(
        variables = context.event.variables + (targetConfig.target to value)
    )
}
```

## References
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/api/config/TargetConfig.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/execution/TargetConfigHandler.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/components/router/FlowRefMuseProcessor.kt`

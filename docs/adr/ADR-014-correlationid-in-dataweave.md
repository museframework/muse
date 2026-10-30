# ADR-014 — correlationId Accessible in DataWeave

## Status
Accepted

## Context
Users need to access the correlation ID within DataWeave expressions for logging, transformation, and routing decisions.

## Decision
`correlationId` is added as a binding in `MuseDataWeaveExecutor` alongside `payload`, `vars`, and `attributes`.
It is accessible in all DataWeave expressions as `#[correlationId]`.

```kotlin
// example
logger { message = "processing event: #[correlationId]" }
withCorrelationId { correlationId = "#[correlationId ++ '-BATCH']" }
```

## Consequences

**Positive:**
- Correlation ID available in all DataWeave expressions
- Can be used for logging, routing, and transformation
- Can be modified or extended (e.g., adding suffixes)
- Consistent with other context variables

**Negative:**
- Adds to DataWeave context bindings
- Slightly more memory per evaluation

## Implementation Details

### DataWeave Context Bindings
```kotlin
class MuseDataWeaveExecutor : DataWeaveExecutor {
    override fun evaluate(
        expression: String,
        context: ExecutionContext
    ): TypedValue<Any> {
        val bindings = mapOf(
            "payload" to context.event.payload?.value,
            "attributes" to context.event.attributes?.value,
            "vars" to context.event.variables.mapValues { it.value?.value },
            "correlationId" to context.event.correlationId  // ← added binding
        )
        
        return dataWeaveEngine.evaluate(expression, bindings)
    }
}
```

### Usage Examples
```kotlin
// Logging with correlation ID
logger {
    message = "#[correlationId]: Processing order #[payload.orderId]"
}

// Creating batch correlation ID
withCorrelationId {
    correlationId = "#[correlationId ++ '-BATCH-' ++ vars.batchId]"
}

// Routing based on correlation ID
choice {
    when_ {
        condition = "#[correlationId startsWith 'BATCH-']"
        // Handle batch events
    }
}
```

## References
- `muse-engine/src/main/kotlin/dev/museframework/engine/dataweave/MuseDataWeaveExecutor.kt`

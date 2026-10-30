# ADR-007 — DwExpression vs String Expressions

## Status
Accepted

## Context
Muse supports two types of expressions: simple string interpolations for logging/messages, and full DataWeave blocks for transformations. We needed distinct types to handle different evaluation paths.

## Decision
Two distinct expression types exist in the model:

| Type | Where used | Syntax | Evaluated by |
|------|-----------|--------|-------------|
| `String` | `LoggerConfig.message` | `"Hello #[payload]"` (interpolation) | `MuseExpressionEvaluator` |
| `DwExpression` | `TransformConfig`, `SetVariableConfig` | pure `%dw 2.0` block or `.dwl` file | `DataWeaveExecutor` directly |

`DwExpression` is a sealed class: `Inline(expression: String)` or `Resource(path: String)`.

## Consequences

**Positive:**
- Clear separation between simple and complex expressions
- Different evaluation engines for different use cases
- Resource-based expressions for large DataWeave scripts
- Type-safe model prevents mixing expression types

**Negative:**
- Two different expression syntaxes to learn
- Different capabilities in different contexts

## Implementation Details

### DwExpression Sealed Class
```kotlin
sealed class DwExpression {
    data class Inline(val expression: String) : DwExpression()
    data class Resource(val path: String) : DwExpression()
}
```

### String Expression (Interpolation)
```kotlin
logger {
    message = "Processing order #[payload.orderId] for customer #[vars.customerName]"
}
```

### DwExpression Inline
```kotlin
transform {
    setPayload = DwExpression.Inline("""
        %dw 2.0
        output application/json
        ---
        {
            id: payload.id,
            total: payload.items reduce (item, acc = 0) -> acc + item.price
        }
    """.trimIndent())
}
```

### DwExpression Resource
```kotlin
transform {
    setPayload = DwExpression.Resource("classpath:transforms/order-to-summary.dwl")
}
```

## References
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/api/expression/DwExpression.kt`
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/api/config/TransformConfig.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/dataweave/MuseDataWeaveExecutor.kt`

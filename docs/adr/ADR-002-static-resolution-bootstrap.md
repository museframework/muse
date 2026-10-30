# ADR-002 — Static Resolution at Bootstrap

## Status
Accepted

## Context
Integration flows reference each other via `FlowRef` and component builders must be resolved before execution. We needed to decide when to resolve these references:
- At runtime (lazy) - more flexible but slower
- At bootstrap (eager) - fail-fast but requires everything to be known upfront

## Decision
All flow references and component builders are resolved **once at startup**.
`FlowRef.ByName` is resolved to an actual `MuseFlow` at bootstrap.
`MuseBootstrapException` is thrown if any reference cannot be resolved.

## Consequences

**Positive:**
- Fail-fast validation catches configuration errors immediately
- Zero lookup overhead at runtime
- No dynamic resolution complexity in the hot path
- Clear error messages at startup vs obscure failures at runtime

**Negative:**
- Cannot add flows dynamically at runtime
- All flows must be known at application start
- Requires complete flow graph to be available

**Exception:**
`MuseContext.flows: Map<String, MuseFlow>` is kept for runtime lookup (DataWeave `lookup()` function, dynamic flow reference by expression).

## Implementation Details

### Bootstrap Resolution
```kotlin
fun bootstrap(config: MuseApplicationConfig): MuseApplication {
    // 1. Create partial context with flows and configs
    val partialContext = MuseContext(
        flows = flows,
        configs = configs,
        // ...
    )
    
    // 2. Apply ALL configurations
    configs.forEach { factory.applyConfig(it, partialContext) }
    
    // 3. Validate flow references (fail-fast)
    val errors = validateFlowRefs(flows)
    if (errors.isNotEmpty()) {
        throw MuseBootstrapException(errors)
    }
    
    // 4. Return MuseApplication
    return MuseApplication(partialContext)
}
```

### Flow Reference Resolution
```kotlin
sealed class FlowRef {
    data class ByReference(val flow: MuseFlow) : FlowRef()
    data class ByName(val name: String) : FlowRef()  // resolved at bootstrap
}
```

## References
- `muse-engine/src/main/kotlin/dev/museframework/engine/app/Bootstrap.kt`
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/validation/FlowRefValidator.kt`
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/api/model/flow/FlowRef.kt`

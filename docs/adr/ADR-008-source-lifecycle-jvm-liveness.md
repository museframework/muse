# ADR-008 — Source Lifecycle and JVM Liveness

## Status
Accepted

## Context
Muse applications need to keep the JVM alive during execution, especially when running without a web server (WebApplicationType.NONE). We needed a mechanism to control JVM lifecycle.

## Decision
`MuseApplication` uses `newSingleThreadContext()` (non-daemon thread) as its coroutine dispatcher.
This keeps the JVM alive when `WebApplicationType.NONE` is used (no Tomcat).

Infinite sources (`FixedSchedulerSource` uses `while(true)`) keep the scope alive.
Finite sources (batch, one-shot) allow clean process exit when their coroutine completes.

## Consequences

**Positive:**
- JVM stays alive without requiring a web server
- Proper cleanup when all finite sources complete
- Supports both long-running and batch processing modes

**Negative:**
- Must explicitly stop infinite sources for clean shutdown
- Non-daemon thread blocks JVM exit

## Implementation Details

### Dispatcher Configuration
```kotlin
class MuseApplication(private val context: MuseContext) {
    private val dispatcher = newSingleThreadContext("muse-application")
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    
    fun start() {
        // Launch consumers for all sources
        context.flows.values.forEach { flow ->
            if (flow is MuseFlow.Flow && flow.initialState == InitialState.STARTED) {
                launchConsumer(flow)
            }
        }
    }
}
```

### Finite Source Example
```kotlin
// One-shot batch: emits 1 event and closes
fixedScheduler {
    repeatCount = 1
    intervalMs = 0
}
```

### Infinite Source Example
```kotlin
// Continuous scheduler: emits indefinitely
fixedScheduler {
    intervalMs = 5000  // Every 5 seconds
    // repeatCount defaults to null (infinite)
}
```

### Clean Shutdown
```kotlin
fun stop() {
    scope.cancel()
    dispatcher.close()
}
```

## References
- `muse-engine/src/main/kotlin/dev/museframework/engine/app/MuseApplication.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/components/source/FixedSchedulerSource.kt`

# ADR-012 — FixedSchedulerSource: repeatCount and initialDelayMs

## Status
Accepted

## Context
The FixedSchedulerSource needed more flexibility for different use cases: one-shot batch triggers, delayed starts, and finite vs infinite execution.

## Decision
`FixedSchedulerConfig` supports two additional optional fields:

```kotlin
val initialDelayMs: Long = 0       // pause before first emission
val repeatCount: Int? = null       // null = infinite, 1 = one-shot batch trigger
```

When `repeatCount` is set, the source emits exactly that many events and closes the channel.
`awaitCompletion()` in `MuseApplication` joins all source jobs — finite sources allow clean process exit.
One-shot batch: `fixedScheduler { repeatCount = 1; intervalMs = 0 }`.

## Consequences

**Positive:**
- Supports batch processing (repeatCount = 1)
- Supports delayed startup (initialDelayMs)
- Supports both finite and infinite execution modes
- Clean shutdown for finite sources

**Negative:**
- Must handle channel closure correctly in finite mode
- Users may be confused by null = infinite semantics

## Implementation Details

### FixedSchedulerConfig
```kotlin
data class FixedSchedulerConfig(
    val intervalMs: Long = 5000,
    val initialDelayMs: Long = 0,
    val repeatCount: Int? = null,  // null = infinite
    override val docName: String,
    override val docId: String
) : Source
```

### Source Implementation
```kotlin
class FixedSchedulerSource(
    private val config: FixedSchedulerConfig,
    private val evaluator: MuseExpressionEvaluator
) : MuseSource {
    override suspend fun start(channel: SendChannel<MuseEventEnvelope>) {
        // Initial delay
        delay(config.initialDelayMs)
        
        var count = 0
        val maxCount = config.repeatCount
        
        while (maxCount == null || count < maxCount) {
            // Emit event
            val envelope = createEnvelope()
            channel.send(envelope)
            
            count++
            
            // If finite and done, close and exit
            if (maxCount != null && count >= maxCount) {
                channel.close()
                return
            }
            
            // Wait for interval
            delay(config.intervalMs)
        }
    }
}
```

### Usage Examples
```kotlin
// Infinite (default)
fixedScheduler {
    intervalMs = 5000
}

// One-shot batch
fixedScheduler {
    repeatCount = 1
    intervalMs = 0
}

// Delayed start, run 10 times
fixedScheduler {
    initialDelayMs = 30000  // 30 second delay
    repeatCount = 10
    intervalMs = 1000       // Every second
}
```

## References
- `muse-engine/src/main/kotlin/dev/museframework/engine/components/source/FixedSchedulerConfig.kt`
- `muse-engine/src/main/kotlin/dev/museframework/engine/components/source/FixedSchedulerSource.kt`

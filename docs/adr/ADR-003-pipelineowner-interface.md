# ADR-003 — PipelineOwner Interface

## Status
Accepted

## Context
Both `FlowBuilder` and `SubFlowBuilder` need to support processors and routers in their pipelines. We needed a way to share DSL extension functions between them while keeping source-specific extensions separate.

## Decision
`FlowBuilder` and `SubFlowBuilder` both implement `PipelineOwner` (with `processor()` and `router()` methods).
All DSL extension functions for processors and routers are defined on `PipelineOwner`.
Source extensions (`scheduler`) remain on `FlowBuilder` only — SubFlow has no source.

## Consequences

**Positive:**
- Single set of DSL functions for processors and routers
- No code duplication between Flow and SubFlow builders
- Type-safe: SubFlow cannot have sources attached
- Clear separation of concerns

**Negative:**
- Additional interface to understand
- Slight complexity in builder hierarchy

## Implementation Details

### PipelineOwner Interface
```kotlin
interface PipelineOwner {
    fun processor(processor: Processor)
    fun router(router: Router)
}
```

### Processor DSL Functions
```kotlin
// All defined on PipelineOwner
fun PipelineOwner.logger(block: LoggerBuilder.() -> Unit)
fun PipelineOwner.transform(block: TransformBuilder.() -> Unit)
fun PipelineOwner.setPayload(block: SetPayloadBuilder.() -> Unit)
fun PipelineOwner.setVariable(block: SetVariableBuilder.() -> Unit)
fun PipelineOwner.removeVariable(block: RemoveVariableBuilder.() -> Unit)
fun PipelineOwner.raiseError(block: RaiseErrorBuilder.() -> Unit)
```

### Router DSL Functions
```kotlin
// All defined on PipelineOwner
fun PipelineOwner.flowRef(flow: MuseFlow, block: FlowRefBuilder.() -> Unit)
fun PipelineOwner.choice(block: ChoiceBuilder.() -> Unit)
fun PipelineOwner.forEach(block: ForEachBuilder.() -> Unit)
fun PipelineOwner.parallelForEach(block: ParallelForEachBuilder.() -> Unit)
fun PipelineOwner.scatterGather(block: ScatterGatherBuilder.() -> Unit)
fun PipelineOwner.async(block: AsyncBuilder.() -> Unit)
fun PipelineOwner.try_(block: TryBuilder.() -> Unit)
fun PipelineOwner.untilSuccessful(block: UntilSuccessfulBuilder.() -> Unit)
fun PipelineOwner.withCorrelationId(block: WithCorrelationIdBuilder.() -> Unit)
```

### Source DSL (FlowBuilder only)
```kotlin
// Only on FlowBuilder - SubFlow has no source
fun FlowBuilder.scheduler(block: SchedulerBuilder.() -> Unit)
```

## References
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/api/dsl/PipelineOwner.kt`
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/builder/FlowBuilder.kt`
- `muse-dsl/src/main/kotlin/dev/museframework/dsl/builder/SubFlowBuilder.kt`

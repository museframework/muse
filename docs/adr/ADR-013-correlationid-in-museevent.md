# ADR-013 — correlationId in MuseEvent

## Status
Accepted

## Context
Distributed tracing and debugging require a unique identifier to track events through the entire pipeline, including sub-flows and external systems.

## Decision
`correlationId: String` is a first-class property of `MuseEvent`, auto-generated as UUID on creation.

```kotlin
data class MuseEvent(
    val payload: TypedValue<Any>? = null,
    val attributes: TypedValue<Any>? = null,
    val variables: Map<String, TypedValue<Any>?> = emptyMap(),
    val error: MuseError? = null,
    val correlationId: String = UUID.randomUUID().toString(),
)
```

The correlation ID propagates along the entire pipeline including sub-flows and flow refs.
Sources that receive a message with a pre-existing correlation ID (e.g. HTTP `X-Correlation-ID` header,
JMS correlation ID) should copy it into `MuseEventEnvelope` rather than generating a new one.

## Consequences

**Positive:**
- First-class support for distributed tracing
- Automatic generation when not provided
- Propagates through entire flow execution
- Enables end-to-end debugging

**Negative:**
- Slightly larger event objects
- Must ensure propagation in all processors

## Implementation Details

### Event Creation
```kotlin
// Auto-generated correlationId
val event = MuseEvent(
    payload = TypedValue(data, MimeType.JSON)
    // correlationId defaults to UUID.randomUUID().toString()
)
```

### Source with Incoming Correlation ID
```kotlin
class HttpListenerMuseSource : MuseSource {
    override suspend fun onRequest(request: HttpRequest): MuseEventEnvelope {
        // Extract from header or generate new
        val correlationId = request.headers["X-Correlation-ID"] 
            ?: UUID.randomUUID().toString()
        
        return MuseEventEnvelope(
            event = MuseEvent(
                payload = TypedValue(request.body, request.contentType),
                attributes = TypedValue(request.headers, MimeType.JSON),
                correlationId = correlationId  // Preserve or generate
            ),
            onResult = { result ->
                // Send HTTP response
            }
        )
    }
}
```

### Propagation in FlowRef
```kotlin
class FlowRefMuseProcessor : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult {
        // correlationId is automatically preserved in childContext()
        val childContext = context.childContext(
            event = context.event.copy(
                // correlationId preserved by default copy()
            )
        )
        
        return ProcessorResult.SuccessWithPipeline(
            context = childContext,
            pipeline = referencedFlow.pipeline
        )
    }
}
```

## References
- `muse-engine-api/src/main/kotlin/dev/museframework/engine/api/event/MuseEvent.kt`
- `muse-engine-api/src/main/kotlin/dev/museframework/engine/api/source/MuseEventEnvelope.kt`

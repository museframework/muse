# Streaming Guide

Muse represents a streaming value with a cursor provider. A repeatable provider
opens an independent cursor for every reader; a non-repeatable provider opens
exactly one cursor and performs no buffering.

## Choosing a Strategy

| Strategy | Storage | Replay | Intended use |
|---|---|---|---|
| `RepeatableInMemory` | Heap | Yes | Small, bounded byte or object payloads |
| `RepeatableFileStore` | Heap prefix plus temp file | Yes | Large byte payloads that may be read again |
| `NonRepeatable` | Original source only | No | One-pass or very large byte/object streams |
| `ImmutableFileCursorStreamProvider` | Existing file | Yes | Files whose lifecycle is already known |

`RepeatableInMemory` throws `StreamMaximumSizeExceededException` when its byte
or object limit is crossed. A configured maximum of `0` means unlimited up to
JVM limits. `RepeatableFileStore` does not impose a total-size limit; disk space
and filesystem errors are propagated.

Create providers through the strategy-aware factory:

```kotlin
val provider =
    StreamingProviders.byteProvider(
        inputStream = source,
        strategy = StreamingStrategy.RepeatableFileStore(
            inMemorySizeBytes = 512 * 1024,
            tempDirectory = "/var/tmp/muse",
        ),
    )
```

Read a cursor with structured cleanup:

```kotlin
val bytes = provider.openCursor().use { cursor -> cursor.readBytes() }
```

Calling `openCursor()` a second time on `NonRepeatable` throws. Its `seek()` is
forward-only and reports the actual position reached if the source ends early.

## DataWeave

Payload, variable, and attribute providers are bound lazily. Creating an
expression context does not claim a cursor:

```kotlin
// Does not consume a non-repeatable payload.
evaluator.evaluate("vars.requestId", event)
```

An expression that reads the provider consumes a non-repeatable cursor. A later
processor cannot read that same provider again.

### CSV rows in a foreach

Read the CSV directly with its reader properties and iterate its payload:

```xml
<file:read config-ref="input" path="records.csv"
           outputMimeType="application/csv; streaming=true; header=true"/>
<foreach collection="#[payload]">
    <logger message="#[payload.recordId]"/>
</foreach>
```

For streamed CSV and stream-capable collection expressions, the built-in split
backend creates an owned iterator. Foreach reads one row or one bounded batch at a
time and closes the reader on completion, failure, or cancellation. The original
payload remains a byte provider, rather than a list containing every parsed row.
CSV MIME reader properties are passed to DataWeave; `charset` controls CSV decoding.
The same stock split path accepts other expressions and input formats; its
progressive behavior depends on their reader settings and expression semantics.

Converting the CSV to `String` and then calling `read` with Java output
materializes the collection. This is behavior of that expression and writer,
not an inherent property of value evaluation. A `TypedValue` can itself contain
a streaming provider. Both direct CSV payload and transformed CSV iteration
with `streaming=true` have progressive and bounded-heap verification.

### Value evaluation and split

Callers choose their consumption shape before backend execution:

```kotlin
val value = evaluator.evaluate(EvaluationRequest.Value("#[vars.answer]"), event)
evaluator.split("#[payload map ((row) -> {id: row.id})]", event).use { rows ->
    while (rows.hasNext()) {
        val row: TypedValue<Any> = rows.next()
        consume(row)
    }
}
```

| Aspect | Contract |
|---|---|
| Result shape | `evaluate` returns one `TypedValue`, potentially streaming. `split` returns typed elements through caller-owned `ExpressionSplit`. |
| Repeatability | Defined by the source provider. Splitting does not make a non-repeatable input repeatable. |
| Lifetime | Close each split on every exit. Event, response and background owners retain their own input claims. |

Mule's foreach `ExpressionSplittingStrategy` calls `ExpressionManager.split`,
then `DataWeaveExpressionLanguageAdaptor.split` and `expressionExecutor.split`,
without ordinary value evaluation first. The stock Muse evaluator now also
forwards split directly to a separate backend operation. The bridge compiles the
original script, executes it into a DW value, obtains its forward iterator and
converts one element at a time. It does not rewrite the expression, call ordinary
value evaluation or snapshot the split collection into a Java list.
See the pinned [Mule splitting strategy](https://github.com/mulesoft/mule/blob/8d1af8a8aa9ee6f95621f617ac848c4a49a5d8a6/core/src/main/java/org/mule/runtime/core/internal/routing/split/ExpressionSplittingStrategy.java#L49-L51)
and [DataWeave split delegation](https://github.com/mulesoft/mule/blob/8d1af8a8aa9ee6f95621f617ac848c4a49a5d8a6/core/src/main/java/org/mule/runtime/core/internal/el/dataweave/DataWeaveExpressionLanguageAdaptor.java#L257-L269).

The stock backend supports array expressions, ranges and objects. Objects split
into maps containing one property per element. Null and scalar results are
rejected by the backend's array coercion. Script headers, imports, local
variables and declared output formats remain part of the original compilation.
Each output item retains its MIME type and charset. Serialized output and
resource-bearing Java maps/lists/arrays are buffered for the current element before
its writing context is disposed; this differs from Mule's output cursor-provider
representation. Memory therefore depends on the input reader, the expression
and the current element's size, not merely the number of elements.

Progressive CSV `map`/`filter`, JSON arrays and XML collection-path reading are
covered by engine tests. Generated CSV foreach with a map expression is covered
by the >256 MiB input/128 MiB heap and unread-tail acceptance tests. This is
implementation evidence, not a bounded-memory guarantee for every expression or
format. An expression that aggregates or retains its input can still require
complete input. Configure reader streaming options explicitly; split does not
force every reader into streaming mode. Property bindings are restored around
opening, advancing and closing so cached scripts retain caller-local values.

`ExpressionSplit` owns a fresh local iterator provider, a cursor and a private
claim through `ExpressionIteration`. The backend owns the evaluation context
until exhaustion, error or close. Closing the result also releases its local
claim and the cursor wrappers created for its bindings, including iterator
bindings that the DataWeave Java reader does not close. Raw caller-supplied
iterators are not adopted. Input providers keep their existing registry/lease
lifetimes. Do not
register the local split provider with a longer-lived flow registry.
Non-repeatable iterator providers close their AutoCloseable delegates at final
resource release.

### Compatibility iteration entry points

`evaluate(String, MuseEvent)`, the typed Value/Iteration overloads and
`openIteration` remain available. Custom evaluators' default split delegates
virtually to `openIteration`; custom backends' default split adapts their existing
capability/value result. Their fallback may still materialize a result. The stock
split override does not use that fallback.

The legacy iteration path is unchanged: its lazy adapter supports direct CSV
payload with `streaming=true`; unsupported expressions may use ordinary value
evaluation, followed by `toList()` for Collections, Arrays and any Iterable.
That path may consume the entire input before returning. Prefer `split` for
stock foreach/expression consumers. Existing mutable-collection snapshots and
borrowed raw-iterator singleton behavior remain in compatibility defaults.

Foreach with `batchSize=1` forwards each item's TypedValue unchanged. Larger
batches retain Muse's existing List-of-values payload behavior; individual MIME
and charset metadata are not retained inside that list. This differs from Mule's
List-of-TypedValue batch representation.

`split(EvaluationRequest.Iteration, event)` also preserves the distinction between
an explicit collection expression and an absent expression. For
`IterationSource.EventPayload`, a custom compatible default reads the original
payload without ordinary evaluation; the stock backend splits `payload` directly.

### Adoption and ownership diagnostics

`StreamingEventResources.registerValueProviders(value, registry)` registers provider
identities in supported nested containers at a value boundary. Payload, variable
and target assignments use it explicitly. It does not wrap raw InputStream or
Iterator values or consume lazy iterables. Event discovery remains a compatibility
safeguard for custom processors and mutations after assignment.

`StreamingResourceRegistry.statistics()` returns an atomic JVM-wide snapshot:

- `activeProviderIdentities`: distinct provider identities with owners;
- `registryClaims`: total independent registry and lease claims;
- `cleanupInProgress`: identities whose final cleanup has been claimed.

These are ownership diagnostics, not open-cursor, heap or buffered-byte counts.
They exclude unmanaged resources outside the ownership protocol. Observe cleanup
before child/process termination when testing leaks; JVM exit alone is not proof
of runtime cleanup. Existing response and background leases keep their own claims
until consumption or background completion. Explicit final cleanup remains the
resource lifetime contract; early reclamation supplements it.

### Managed providers and early reclamation

`StreamingProviders.byteProvider` and `objectProvider` return managed handles.
File, SFTP, Database and HTTP adapters also publish managed handles. The execution
registry tracks their independent cleanup state without keeping the application
handle strongly reachable. When an obsolete response is no longer referenced,
the JVM Cleaner releases its backing resource and removes its registry entries
before a long flow finishes.

Open cursor decorators and independent `StreamingResourceLease` instances keep
their handle strongly reachable. Close each cursor and lease explicitly. Shared
registries still release a provider only after their last explicit claim, and
the execution's final cleanup releases surviving providers even if an application
reference remains. Early reclamation is GC-dependent, not an immediate release
guarantee when a variable is overwritten.

Adopt a custom byte or iterator provider before publishing it:

```kotlin
val handle = StreamingProviders.managed(rawProvider)
context.streamingResources.register(handle)
return TypedValue.of(handle)
```

Use the returned handle rather than the raw delegate. Adoption is idempotent
for an existing handle and canonical for a still-live delegate/handle pair.
Adopting a raw provider already registered elsewhere, or registering an adopted
raw delegate, is rejected. Generic custom providers registered without adoption
preserve their legacy strong identity and end-of-execution lifetime.

The outermost decorator must be adopted; wrapping only a decorator's backing
provider cannot make the decorator reclaimable. BackgroundStreamPreparation
is preserved when implemented by the delegate. Adoption and cleanup never open
a cursor or request a lazy size property. Standard factories preserve passive
size metadata; a custom adopted provider has unknown size after release unless
its size was explicitly queried while active.

The file-store factory starts with a small byte buffer and grows up to the
configured in-memory threshold. This changes allocation pressure, not the
threshold, repeatability or spill policy.

## HTTP Usage

HTTP models are provider-first. A streaming handler consumes
`HttpRequest.bodyProvider`:

```kotlin
val requestProvider = request.bodyProvider ?: error("Expected request stream")
val result = requestProvider.openCursor().use(::parseRequest)
```

A streaming response returns a provider without opening it:

```kotlin
HttpResponse(
    statusCode = 200,
    headers = mapOf("Content-Type" to "application/octet-stream"),
    bodyProvider = provider,
)
```

The Spring adapter copies that cursor directly to the servlet output stream. The response carries a
retained claim (`HttpResponse.bodyLease`) for exactly the provider it exports, and the adapter closes
that lease after serialization, HEAD/no-body handling, a write error or disconnect, or a
response-construction failure. An inbound request provider is claimed by the adapter's own inbound
registry for the duration of the request; a provider the engine also adopted keeps its engine claim,
and a provider nobody adopted is released by that inbound claim when the request is over. For the
exact request-as-response case under `non-repeatable`, the adapter first detaches the response into the
configured file store. This avoids an HTTP/1.x half-duplex deadlock when the upstream server starts
writing before the client has finished sending a large request.

Outbound requests also accept a provider. The Spring client writes it through a
streaming output callback. Repeatable responses spool directly from the network
stream; non-repeatable responses keep the network response open until the
provider is released.

`HttpRequest.body`, `HttpResponse.body`, and `HttpOutboundRequest.body` remain
valid for small values that are already materialized. They are not the streaming
path.

## Spring Configuration

Select one strategy globally for Spring HTTP request and response providers:

```properties
muse.streaming.strategy=repeatable-file-store
```

File-store settings (also used for the request-as-response safety detachment
when the selected strategy is `non-repeatable`):

```properties
muse.streaming.file-store.in-memory-size-bytes=524288
muse.streaming.file-store.temp-directory=/var/tmp/muse-streaming
muse.streaming.file-store.delete-files-on-close=true
```

In-memory byte and object settings:

```properties
muse.streaming.strategy=repeatable-in-memory
muse.streaming.in-memory.initial-buffer-size-bytes=524288
muse.streaming.in-memory.buffer-size-increment-bytes=524288
muse.streaming.in-memory.max-buffer-size-bytes=1048576
muse.streaming.in-memory.initial-object-buffer-size=500
muse.streaming.in-memory.object-buffer-size-increment=100
muse.streaming.in-memory.max-object-buffer-size=1000
```

One-pass transport:

```properties
muse.streaming.strategy=non-repeatable
```

This remains one-pass at normal transport boundaries. Returning the listener's
exact request provider as its response is the deliberate exception described
above: it is detached to disk before response serialization.

The provider-aware default leaves `HttpRequest.body` null. During migration, a
repeatable strategy can opt into the old duplicate body field:

```properties
muse.streaming.legacy-body-materialization=true
```

This compatibility option reads the entire request into a byte array, defeats
bounded-memory behavior, and cannot be combined with `non-repeatable`.

## Ownership Rules

Ownership is reference-counted by provider identity, not by a single exclusive owner. A provider can be
claimed by the request, an accepted async branch and a response at the same time; `close()` and
`releaseResources()` run only when the last claim is closed.

| Boundary | Owner after the boundary | Release point |
|---|---|---|
| Source request enters engine | Engine execution registry (`MuseEventEnvelope.executionResources`) | End of the event, unless a branch holds its own claim |
| Processor returns a new provider | Engine execution registry | End of event, unless transferred |
| HTTP source exports reply provider | Retained lease (`HttpResponse.bodyLease`), built while `replyResources` is open | After serialization, HEAD/no-body handling, write error/disconnect or response-construction failure |
| HTTP client returns a provider | Calling processor/engine registry | End of event or later reply transfer |
| Direct `HttpServer` handler receives a provider | Adapter-owned claim (`StreamingResourceRegistry.ownedClaim`) | Handler completion or response serialization |
| Accepted async branch | Application-owned branch registry | Branch finalization, after structured descendants and provider cleanup |

Processors should return newly created providers in their event immediately. If a processor may throw
before returning, register first:

```kotlin
val provider = context.streamingResources.register(createProvider())
// Work that can fail follows.
```

Registry cleanup is idempotent, and attempts every provider even if one cleanup operation fails.
Membership and shared ownership are tracked by object identity, not `equals()`. Never call `close()` or
`releaseResources()` on a provider while `isManaged` reports that a registry still owns it: release the
owning registry or its retained lease instead.

## Async Branches and Request-Bound Input

A streaming value handed to an accepted async branch stays usable after the main response is
available: the branch holds its own claim, so main-response completion never closes a provider a live
branch still reads (ADR-020), and request cancellation after acceptance does not cancel the branch
(ADR-021).

A non-repeatable provider whose backing is the live servlet request cannot gain that guarantee from
reference counting alone, so the Spring adapter wraps the inbound body in a provider implementing
`BackgroundStreamPreparation`. Admission calls `prepareForBackgroundUse()` before acceptance, which
detaches the remaining request input into the configured file-store strategy and keeps the wrapper's
single-cursor contract: promotion is not replayability. If the cursor was already claimed, a
preparation overlaps, or the transport is gone, admission is rejected with
`INPUT_NOT_RETAINABLE` instead of silently rewinding the input; a failed or cancelled preparation
removes its provisional files and releases the provider.

## Failure Behavior

- A listener request that exceeds an in-memory byte limit receives HTTP 413.
- Network, filesystem, and provider failures are propagated explicitly; the
  adapter does not replace them with an empty body.
- HTTP validation error messages read at most 64 KiB from a streaming response
  and append ` [truncated]` when more data exists.
- Source timeouts, cancellations, and response-generation failures release the claims the source
  holds, so an event that never executes still releases its registered providers exactly once.
- A response-construction failure, a write error or a disconnect closes exactly the lease that
  response owns; a provider a live async branch still claims is untouched.

The architectural motivations and rejected alternatives are described in the
[Streaming Implementation Rationale](streaming-implementation-rationale.md).
The accepted ownership decisions are recorded in
[ADR-018](adr/ADR-018-repeatable-streaming.md), [ADR-020](adr/ADR-020-async-streaming-lifetime.md) and
[ADR-021](adr/ADR-021-async-request-cancellation.md). Extension-defined algorithms that call declared
bodies and submit background work are described in the
[Execution Policy Extension Guide](execution-policy-extension-guide.md).

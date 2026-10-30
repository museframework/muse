# Streaming Implementation Rationale

This document explains why Muse's streaming subsystem has its current shape.
It complements the user-facing [Streaming Guide](streaming-guide.md) and the
formal decision record in
[ADR-018](adr/ADR-018-repeatable-streaming.md). It focuses on architectural
intent, ownership boundaries, and trade-offs rather than configuration syntax.

## Problem Being Solved

An integration engine cannot assume that every payload is a small value that
can safely become a `String` or `ByteArray`. HTTP bodies, files, and generated
object sequences may be large, may be readable only once, or may need to be
read by more than one processor.

Streaming also introduces a lifecycle problem. Pipeline execution can finish
before an HTTP adapter has serialized the resulting body. Releasing resources
at the end of the pipeline is therefore too early, while relying on garbage
collection is too late and non-deterministic.

The design has four goals:

1. Keep memory use bounded when the selected strategy permits it.
2. Make replayability an explicit choice rather than an accidental behavior.
3. Assign every backing resource a deterministic owner and release point.
4. Preserve streaming through engine, DataWeave, and HTTP boundaries without
   weakening existing small-payload APIs.

"Streaming" does not always mean "no buffering." Repeatable strategies must
store data somewhere. The important distinction is whether storage is bounded
and explicit, and whether a one-pass source can remain one-pass when replay is
not required.

## Architectural Shape

```text
transport or file source
        |
        v
MuseCursorProvider  -- owns the backing resource
        |
        +-- opens one or more MuseCursor instances
        |
        v
MuseEvent / processor / DataWeave
        |
        v
StreamingResourceRegistry  -- owns lifecycle during execution
        |
        +-- release at event completion
        `-- explicit transfer to a response adapter
                              |
                              v
                     serialize, then release
```

The provider and registry solve different problems. A provider defines how a
value is read and replayed. A registry records who is responsible for releasing
that provider. The streaming registry is not the component-registration SPI,
and a cursor provider is not a `MuseConfigProvider`.

## Why Providers and Cursors Are Separate

`MuseCursorProvider` owns the data source or storage. `MuseCursor` owns only an
individual read position. This split makes replay semantics visible:

- a repeatable provider creates an independent cursor for each reader;
- a non-repeatable provider accepts exactly one cursor request;
- closing a cursor ends one read without necessarily releasing the provider;
- releasing the provider invalidates every remaining cursor and frees its
  backing resource.

Processors should use cursor-scoped cleanup, normally with `use`, while the
execution owner controls provider cleanup. Keeping these lifecycles separate
prevents one processor from deleting a temporary file merely because it has
finished its own read.

Cursor positions are `Long`. File-backed payloads can exceed 2 GiB, so an
`Int` position would corrupt seeking even if the I/O APIs themselves supported
the file size.

## Why Strategies Are Explicit

Muse exposes the storage/replay decision as `StreamingStrategy` instead of
selecting a hidden global behavior inside each component.

| Strategy | Rationale | Principal trade-off |
|---|---|---|
| `RepeatableInMemory` | Lowest read latency for small payloads and object sequences | Heap use grows with payload size; a configured maximum should normally be enforced |
| `RepeatableFileStore` | Supports replay while keeping heap use proportional to the configured prefix | Uses temporary disk space and requires deterministic deletion |
| `NonRepeatable` | Preserves the original one-pass source without pre-buffering | A second consumer fails and processing order becomes observable |
| `ImmutableFileCursorStreamProvider` | Reuses an existing immutable file with independent seekable readers | The external file lifecycle must already be understood |

The in-memory implementation grows in configured increments and rejects a
payload before exceeding its maximum. A maximum of zero is intentionally
interpreted as unlimited up to JVM limits; it is useful for compatibility but
does not provide bounded-memory protection.

The file-store implementation retains only the configured prefix in memory and
spools the remainder directly to a temporary file. It does not impose a total
payload limit, because disk quotas and deployment policy are environment
concerns. Temporary-file creation, write failures, and deletion failures remain
visible rather than being converted into an empty payload.

Object streams currently support in-memory and non-repeatable strategies.
Serializing arbitrary objects into a repeatable file store would require a
separate serialization contract and is deliberately outside the current scope.

## Why Ownership Is Registry-Based

Provider cleanup cannot be inferred reliably from local variables. A provider
may be introduced in the initial event, returned by a processor, produced by an
error handler, passed through several processors, or exported as an HTTP
response. `StreamingResourceRegistry` therefore acts as the ownership ledger
for one root execution.

The engine registers providers from the initial event and after every pipeline
result, including failure and error-handler results. A processor that creates a
provider and can throw before returning it must register it immediately:

```kotlin
val provider = context.streamingResources.register(createProvider())
// Operations that can fail follow.
```

The registry uses object identity rather than `equals()`. Two provider objects
can compare equal while still owning different streams or temporary files.
Value-based membership could then leak one resource or release the wrong one.

Ownership counts are shared across registries, also by identity. This protects
a provider observed by more than one execution owner: only the last registry
performs cleanup. A transfer to an external owner succeeds only when the
transferring registry is the sole owner, so one execution cannot silently steal
a resource from another.

Registration, transfer, and release are synchronized as one lifecycle state
machine. Registration racing with `releaseAll()` either becomes registry-owned
or is cleaned immediately; it cannot land in an unowned gap. Cleanup attempts
every provider even if one operation fails and reports the collected failures
as `StreamingCleanupException` afterward.

`MuseCursorProvider.isManaged` exposes only the ownership fact needed at an
adapter boundary. For example, the Spring server must not release an inbound
provider after a handler failure if an engine registry still owns it.

### Why managed handles do not stay strongly retained by the registry

A long foreach can overwrite a response variable many times. Strongly retaining
every historical response in the execution ledger makes replay storage grow with
iteration count even though only the latest response is still usable. Managed
application handles are therefore separated from cleanup state. Registry entries
and global ownership counts retain that state, while a Cleaner registration
observes the handle without a strong backreference. This follows Mule's managed
wrapper, weak tracking and janitor separation.

The state retains the raw delegate needed for cleanup. A closed cursor drops its
handle pin; an open cursor or retained lease keeps the handle alive. This handles
aliases in arbitrary application containers without walking or consuming lazy
values. Final cleanup and automatic cleanup converge on one idempotent release
transition. Automatic cleanup failures are logged; explicit release reports
StreamingCleanupException. Registry entries are removed from an identity index
and linked drain order in constant time, with provider callbacks outside locks.

Adoption is explicit for custom providers and transfers application use to the
returned interface. Mixing a registered raw delegate with its managed handle is
rejected. The generic raw registration API remains compatible and strongly owned
until execution completion. These distinct contracts avoid silently changing
custom provider identity while giving standard byte/object providers early
reclamation.

Cleanup must not request metadata that initializes a lazy File provider. Only
passive metadata supplied by standard factories or queried by an actual consumer
is cached after release. The file-store buffer also grows from a small capacity
rather than preallocating the full memory threshold for every small HTTP ACK.
Neither change alters source repeatability or expression iteration semantics.

## Why Reply Ownership Uses a Handshake

The HTTP source and engine communicate asynchronously through a
`MuseEventEnvelope`. The engine completes pipeline execution before the Spring
server writes the response body. An unconditional end-of-event cleanup would
therefore invalidate a response provider before serialization.

The reply-resource handshake makes this boundary explicit:

1. The engine produces the result event.
2. The source maps that event to an HTTP response and identifies the exact
   provider retained by the response.
3. The engine transfers that provider out of its registry and releases every
   provider that was not retained.
4. The HTTP adapter serializes the response and releases the transferred
   provider on both success and failure.

Timeout, cancellation, and response-mapping failure complete the handshake
with an empty set. The engine then remains responsible for all cleanup and does
not wait indefinitely for a transfer decision.

This protocol is preferable to making every source own every event resource:
the engine has complete visibility during execution, while the source knows
which exact value crosses the transport boundary.

## Why DataWeave Bindings Are Lazy

Creating an expression context is not evidence that an expression will consume
the payload, a variable, or the attributes. Opening cursors eagerly would claim
the only cursor of a non-repeatable provider even for an expression that reads
only unrelated metadata.

DataWeave therefore receives lazy `InputStream` and iterator wrappers. A cursor
is opened only on the first read or iteration operation. Repeatable providers
still support multiple actual consumers; non-repeatable providers fail clearly
when a second consumer really attempts to read.

## Why HTTP Is Provider-First

The HTTP models retain byte-array fields for already-materialized small values,
but provider fields are the primary streaming path.

For outbound requests, Spring receives a streaming output callback that copies
from a provider cursor directly to the network output. For repeatable inbound
responses, the configured provider consumes the network stream directly into
its selected storage. For non-repeatable responses, the live HTTP response
remains open until provider release so no intermediate buffer is required.

On the server side, request bodies become providers and response cursors are
copied directly to the servlet output stream. Duplicate `ByteArray`
materialization is disabled by default. The explicit legacy compatibility flag
exists for migrations, but it is rejected with the non-repeatable strategy
because the two contracts conflict.

Maximum-size violations are represented explicitly and map to HTTP 413 at the
listener boundary. Validation errors consume only a bounded prefix of a remote
error body. These choices prevent diagnostics from reintroducing the same
unbounded allocations that streaming is intended to avoid.

### The Non-Repeatable Request-as-Response Exception

Returning the exact non-repeatable request provider as the response is unsafe
on a servlet HTTP/1.x connection. A sufficiently large exchange can deadlock:
the server is still reading the request while the client waits for response
progress, and both socket buffers can fill.

The Spring server detects only this identity-equal request-as-response case and
detaches it into the configured repeatable file store before writing response
headers. Normal non-repeatable forwarding remains unbuffered. The narrow
exception favors transport correctness while retaining bounded heap use.

## Alternatives Not Chosen

- **Always materialize to `ByteArray`:** simple ownership, but heap use scales
  with total payload size and large integrations fail unpredictably.
- **Always spool to disk:** predictable heap use, but imposes I/O and cleanup
  cost even on one-pass forwarding and small payloads.
- **Rely only on `AutoCloseable` at call sites:** insufficient when a resource
  crosses processors or survives pipeline completion.
- **Release everything when the pipeline returns:** invalidates providers still
  needed by response serialization.
- **Let the transport own all resources:** the transport cannot observe
  providers introduced or discarded inside the pipeline.
- **Track providers using equality:** unsafe because lifecycle ownership belongs
  to resource identity, not value semantics.
- **Open DataWeave cursors eagerly:** accidentally consumes non-repeatable
  values that an expression never reads.

## Verification Boundaries

The implementation is tested at complementary levels:

- provider tests cover replay, limits, cleanup, concurrency, sparse files, and
  failure aggregation;
- engine integration tests cover discovery and release across success, failure,
  exceptions, error handling, pass-through, and reply transfer;
- Spring integration tests cover bounded server/client behavior, maximum-size
  errors, managed ownership, and the HTTP/1.x detachment case;
- packaged E2E tests exercise file-store and genuinely non-repeatable payloads
  across a separately launched application and real HTTP boundaries.

This separation matters: provider correctness alone cannot prove that a live
HTTP response survives engine completion, while E2E coverage alone cannot
exhaustively prove cursor and concurrency invariants.

## Practical Guidance

Use `RepeatableInMemory` only when a meaningful maximum is known. Prefer
`RepeatableFileStore` when consumers may replay an unbounded byte payload. Use
`NonRepeatable` when the flow has one deliberate consumer and avoiding storage
is more important than replay.

Regardless of strategy:

- close every cursor locally;
- let the current registry release managed providers;
- register a new provider before subsequent code that can fail;
- transfer ownership only at a defined asynchronous boundary;
- test both success and failure cleanup whenever a new transport or source is
  made provider-aware.

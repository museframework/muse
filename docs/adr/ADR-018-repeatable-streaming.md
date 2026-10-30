# ADR-018 — Streaming Payloads and Resource Ownership

## Status

Accepted; amended 2026-08-26 to include non-repeatable streaming and explicit
HTTP reply ownership.

## Context

Muse flows must move HTTP and transformation payloads without forcing every
boundary to allocate a complete `String` or `ByteArray`. Some integrations read
a payload more than once; others must forward a large, one-shot source without
buffering it at all. Providers can also outlive pipeline execution while an HTTP
adapter is still serializing the response, so cleanup cannot be based only on
the end of processor execution.

## Decision

`TypedValue` stores streaming values as cursor providers from
`muse-engine-api`. The supported strategies are:

- `RepeatableInMemory`: buffers byte or object streams in configurable growth
  increments and enforces configured maximum sizes. A maximum of `0` means
  unlimited up to JVM collection limits.
- `RepeatableFileStore`: keeps the configured byte prefix in memory and spools
  the rest directly to a temporary file.
- `NonRepeatable`: wraps a byte or object source without buffering and permits
  exactly one cursor. Byte cursors support forward-only seek.
- `ImmutableFileCursorStreamProvider`: opens repeatable cursors over an existing
  immutable file.

File cursor positions are `Long`, including positions beyond 2 GiB. Opening and
releasing cursors is atomic at the provider boundary.

## Lifecycle and Ownership

Each root `ExecutionContext` owns a `StreamingResourceRegistry`. The engine
registers providers found in the initial event and after every processor result,
including failure results and providers introduced by error handling. Processors
that allocate a provider and then throw before returning it must register it
immediately in `context.streamingResources`.

Registration is atomic with registry release. Cleanup attempts `close()` and
`releaseResources()` on every provider and aggregates failures instead of
abandoning later resources.

The source reply boundary uses an explicit ownership handshake:

1. The engine completes the event reply.
2. The source identifies the exact providers retained by its response adapter.
3. The engine transfers those providers out of the registry and releases all
   others.
4. The adapter releases each transferred provider after response serialization
   succeeds or fails.

Timeout, cancellation, and response-generation failure complete the handshake
with an empty set, so the engine retains cleanup responsibility.

## DataWeave Behavior

Payload, variable, and attribute providers are exposed through lazy stream or
iterator bindings. Constructing the DataWeave context does not open a cursor.
An expression that does not consume a non-repeatable value therefore does not
claim its only cursor.

## HTTP Boundary Behavior

Spring HTTP adapters are provider-first:

- Listener request streams are converted directly into the selected provider.
  `HttpRequest.body` is null by default.
- Outbound provider bodies are copied through Spring's streaming output callback
  without an intermediate byte array.
- Repeatable client responses spool directly from the live response stream.
- Non-repeatable client responses retain the underlying response until provider
  release.
- Server responses copy a provider cursor directly to the servlet output stream
  and release the provider afterward.
- When a `NonRepeatable` listener returns its exact request provider as the
  response, the server detaches it to the configured repeatable file store
  before writing response headers. This narrowly scoped spool prevents an
  HTTP/1.x half-duplex deadlock for large request-as-response flows.
- In-memory maximum-size violations at the listener boundary return HTTP 413;
  streaming I/O failures remain explicit rather than becoming empty bodies.

The byte-array fields remain for already-materialized data. Duplicate inbound
materialization is available only through
`muse.streaming.legacy-body-materialization=true`, is disabled by default, and
is rejected with `NonRepeatable`.

## Consequences

Positive consequences:

- The Spring transport uses bounded copy buffers; repeatable file-store payloads
  use memory proportional to their threshold rather than total payload size.
- Non-repeatable payloads can cross listener, engine, outbound client, and
  response boundaries without a forced buffer except for the exact
  request-as-response safety case.
- Temporary files and live HTTP responses have a single, testable owner.
- Registry membership is identity-based, and transport cleanup does not release
  a provider still owned by an engine registry, including handler-failure paths.
- Unused DataWeave bindings do not accidentally consume one-shot streams.

Trade-offs and limitations:

- A non-repeatable provider fails on a second cursor request; processors must not
  assume replayability.
- Exact non-repeatable request-as-response flows incur a file-store spool; the
  file-store threshold, directory, and cleanup settings therefore also apply
  when `muse.streaming.strategy=non-repeatable`.
- Repeatable file-store object iterators are not implemented.
- The explicit legacy request-body compatibility option can allocate the entire
  payload and should be used only during migration.
- A source implementing the reply handshake incorrectly can delay cleanup; every
  handshake-owning source must complete it on all terminal paths.

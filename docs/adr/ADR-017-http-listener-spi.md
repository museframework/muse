# ADR-017 — HTTP Connector API and Adapter SPI

## Status

Accepted; amended on 2026-08-28 after extraction of `muse-engine-api` and the
HTTP connector family.

## Context

Muse must expose HTTP listeners without coupling its declarative DSL or runtime
contracts to Spring, servlet APIs, Netty, or another server implementation.
Connector configuration, event execution, and framework adapters have different
dependency and lifecycle responsibilities.

The original design placed a proposed listener SPI in the former `muse-core`
module. That module no longer exists. Declarative types now live in `muse-dsl`,
runtime contracts in `muse-engine-api`, and HTTP contracts in
`muse-http-connector-api`.

## Decision

The HTTP connector is split into public API, framework-neutral runtime,
framework adapter, testkit, and Babelfish provider modules.

### Public HTTP API

`muse-http-connector-api` owns:

- listener and request configuration;
- connection references and lifecycle configuration;
- listener and request DSL functions;
- framework-neutral request, response, attribute, and error models;
- `HttpServer` and HTTP client contracts;
- `HttpListenerServerInitializer` and `HttpClientInitializer` SPI;
- `HttpListenerMuseSource` and its event-envelope mapping.

The API depends on `muse-dsl` and `muse-engine-api`, not `muse-engine` or a
framework adapter.

### Adapter discovery

`HttpListenerServerInitializer.load()` and `HttpClientInitializer.load()` use
Java `ServiceLoader`. An application must place one appropriate adapter on its
runtime classpath. Absence of an initializer fails with a diagnostic naming the
required adapter dependency.

Adapters package their implementation class in the corresponding
`META-INF/services` resource.

### Runtime execution

`HttpListenerMuseSource` obtains an `HttpServer` from the initializer, registers
a framework-neutral route handler, maps requests to `MuseEvent`, and completes
responses through `MuseEventEnvelope`.

The source declares `WebServerRequirement` through the infrastructure contracts
in `muse-engine-api`. It calls the adapter's `start()` according to listener
lifecycle configuration. An adapter whose container owns startup may implement
`start()` as an idempotent no-op.

The framework-neutral outbound request processor lives in
`muse-http-connector` and depends only on the HTTP API, Muse DSL, and Engine API.

### Spring adapter

`muse-http-connector-spring` implements the HTTP server and client contracts. It
maps Spring requests and responses at the adapter boundary; Spring and servlet
types do not leak into the public HTTP API or engine contracts.

Spring owns its embedded server lifecycle. The adapter registers Muse route
handlers against that running container and clears them on shutdown.

### Resource ownership

HTTP request and response bodies may use streaming cursor providers. The source
and adapter follow the explicit ownership transfer defined by ADR-018: the
engine releases unclaimed providers, while a response adapter releases providers
transferred to it after serialization succeeds or fails.

## Consequences

Positive consequences:

- connector APIs and runtime remain independent of Spring;
- other adapters can implement the same initializer and server contracts;
- framework-specific types remain at the edge;
- missing adapters fail explicitly;
- listener, engine, and response-resource ownership are independently testable;
- the HTTP Babelfish provider depends on the public HTTP API rather than the
  runtime or Spring adapter.

Trade-offs and limitations:

- applications must provide an adapter explicitly;
- `ServiceLoader` packaging is an additional runtime contract;
- lifecycle behavior varies between self-managed and container-managed servers;
- API, runtime, adapter, testkit, and mapper modules add reactor complexity.

## Testing Strategy

- `muse-http-connector-api` tests public configuration, models, DSL, and source
  contracts;
- `muse-http-connector` tests framework-neutral request processing;
- `muse-http-connector-testkit` defines reusable client/server adapter
  contracts;
- `muse-http-connector-spring` verifies Spring wiring and real HTTP behavior;
- `muse-http-babelfish-mappers` verifies XML mapping and Kotlin emission;
- packaged E2E tests verify generated applications and transport boundaries.

## Adding Another Adapter

A new adapter must:

1. depend on `muse-http-connector-api` and the required Engine API contracts;
2. implement the listener and/or client initializer;
3. provide the matching `META-INF/services` entry;
4. keep framework-specific types out of public models;
5. satisfy the reusable adapter contracts;
6. implement idempotent startup and cleanup consistent with its container;
7. honor streaming ownership and correlation-ID propagation.

## References

- `muse-engine-api/src/main/kotlin/dev/museframework/engine/api/infrastructure/InfrastructureRequirement.kt`
- `muse-http-connector-api/src/main/kotlin/dev/museframework/http/api/server/HttpListenerServerInitializer.kt`
- `muse-http-connector-api/src/main/kotlin/dev/museframework/http/api/server/HttpServer.kt`
- `muse-http-connector-api/src/main/kotlin/dev/museframework/http/api/source/HttpListenerMuseSource.kt`
- `muse-http-connector-spring/src/main/kotlin/dev/museframework/connectors/http/spring/SpringHttpListenerServerInitializer.kt`
- `muse-http-connector-spring/src/main/kotlin/dev/museframework/connectors/http/spring/SpringHttpServer.kt`
- [ADR-018](ADR-018-repeatable-streaming.md)
- [HTTP listener guide](../http-listener-guide.md)

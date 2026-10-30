# ADR-020 — Database Connector Boundaries and JDBC Driver Isolation

## Status

Accepted on 2026-09-01.

## Context

Muse needs a database connector that follows the observable JDBC behavior of
MuleSoft Database Connector 1.16 without importing Mule runtime types or tying
the connector to Spring. The reference connector exposes generic JDBC and
`DataSource` connections; select, query-single, insert, update, delete, bulk
DML, DDL, script, and stored-procedure operations; and a polling row source.

JDBC drivers are application-supplied libraries. `DriverManager` keeps a global
registry and filters drivers by the caller's defining classloader. Registering a
driver globally from a connector therefore creates two risks in embedded or
plugin runtimes: a driver loaded in a child realm can become invisible to the
connector, and a registered driver can retain an otherwise disposable realm.
Mule addresses the wider problem with artifact classloaders, child-first lookup
policies, external-library declarations, and explicit disposal. Muse currently
packages applications as ordinary JVM or Spring Boot applications and has no
artifact-classloader lifecycle to own dynamically created driver realms.

## Decision

### Module boundaries

The database family consists of three modules:

```text
muse-db-connector-api
    ↑
muse-db-connector

muse-babelfish + muse-db-connector-api
    ↓
muse-db-babelfish-mappers
```

- `muse-db-connector-api` owns public connection/configuration models, typed
  configuration references, operation and source models, result/error types,
  and Kotlin DSL builders. It depends on Muse DSL contracts, not the engine
  implementation.
- `muse-db-connector` owns JDBC execution, named-parameter parsing, streaming
  cursor ownership, row polling, and component registration. It depends only on
  the public database API and Engine API.
- `muse-db-babelfish-mappers` owns Mule `db` XML mapping and Kotlin emission. It
  depends on Babelfish, Muse DSL, and the database API, never the runtime.

No Spring adapter is introduced. JDBC and `javax.sql.DataSource` are
framework-neutral, and the runtime registrar is discovered through the existing
`MuseComponentRegistrar` service. A Spring application can inject a managed
`DataSource` into a typed `DbConfig`; a generic JDBC configuration needs no
Spring service.

### Public operation contract

The connector implements the Mule Database Connector operation categories:

- `select` and `query-single`;
- `insert`, `update`, and `delete`;
- `bulk-insert`, `bulk-update`, and `bulk-delete`;
- `execute-ddl` and `execute-script`;
- `stored-procedure`;
- `on-table-row` polling with optional watermark and ID columns.

Statements support colon-prefixed named parameters, explicit JDBC parameter
types, query timeout, fetch size, maximum rows, column-number result keys,
generated-key selection, target variables, inline or file-backed scripts, and
stored-procedure IN, OUT, and INOUT parameters. Bulk and script executions use
one connection and commit or roll back as one local unit when the acquired
connection starts in auto-commit mode.

Named-parameter scanning targets Mule Database Connector 1.16 observable
behavior (Spring-style quote skipping): ordinary `'` closes at the next `'`,
with no MySQL-style backslash escapes inside ordinary strings; outside quotes,
`\:` is a literal colon. Prefer `''` or binding the value through the input
parameter map for apostrophes. PostgreSQL `::` casts, dollar-quotes, and
`E'...'` escape strings are Muse extensions beyond that baseline.
`execute-script` splits on Mule's `;[\\r\\n]+` separator, so a semicolon only
starts a new statement when followed by a newline.

A select returns a `MuseCursorIteratorProvider` and registers it immediately
with the execution's `StreamingResourceRegistry`. The first cursor reads the
live result set lazily while repeatable in-memory mode caches rows for later
cursors. Non-repeatable mode permits one cursor and retains no replay buffer.
Connection, statement, and result-set ownership ends when the provider is
released, exhausted, or fails.

Cross-operation transaction joining is not exposed. Muse does not yet have a
typed transaction context or a processor completion hook that can safely own a
connection across a nested pipeline. Adding a connector-only transaction flag
would imply atomicity that the runtime cannot provide. A future engine-wide
transaction contract can extend this decision without changing statement
models.

### JDBC driver classloader isolation

A generic connection resolves its driver with this order:

1. the explicit classloader supplied on the generic connection model;
2. the current thread context classloader;
3. the database runtime module's defining classloader.

The runtime loads and instantiates the configured `java.sql.Driver` in that
realm, temporarily installs that realm as the thread context classloader while
connecting, and invokes `Driver.connect(url, properties)` directly. It does not
call `DriverManager.registerDriver` and does not depend on the global driver
registry. Each database configuration can therefore use a driver from its own
realm without retaining that realm in JVM-global state.

Muse does not create a `URLClassLoader` from arbitrary driver paths. The host
application, build tool, or embedding container owns driver artifacts and the
lifecycle of any explicit classloader it supplies. This keeps ownership
correct until Muse has a disposable application-artifact classloader contract.
A supplied `DataSource` likewise remains application-owned; the connector closes
borrowed `Connection` objects but never closes the `DataSource`.

Babelfish provider isolation continues to use the Maven plugin's existing
plugin realm. The database mapper provider joins the
`all-built-in-providers` Maven Invoker fixture so core, HTTP, APIKit, and
database providers are proven visible and collision-free in one isolated realm.

### Verification boundaries

- API and runtime unit tests defend builders, validation, SQL parsing, cursor
  lifecycle, and explicit driver-classloader selection.
- Runtime integration tests use an embedded H2 JDBC engine in PostgreSQL
  compatibility mode for every operation family, local rollback, and row
  polling. This keeps the connector contract executable without Docker while
  still exercising a real JDBC driver and database engine.
- The database mapper module owns mapper/emitter tests, provider discovery, and
  an independent XML translation integration test.
- Maven Invoker owns provider plugin-realm composition.
- `muse-packaged-e2e` starts a packaged Spring Boot application with the H2
  driver nested in the executable JAR and exercises DDL, DML, query results,
  registrar discovery, and HTTP response handling in a separate JVM.

## Consequences

Positive consequences:

- applications choose JDBC vendors without a runtime or Spring dependency;
- multiple host-owned driver realms do not use global driver registration;
- database rows can stream without pre-materializing an unbounded result set;
- public APIs remain typed and mapper/runtime dependencies follow repository
  boundaries;
- a real JDBC engine, plugin-realm generation, and packaged-process execution
  cover the principal integration failures.

Trade-offs and limitations:

- generic connections open a physical connection per operation unless the user
  supplies a pooled `DataSource`;
- repeatable selects retain consumed rows in memory and enforce the configured
  object-buffer limit; repeatable file-store object streaming is unavailable in
  Engine API;
- driver JAR discovery and disposal remain the host's responsibility;
- local bulk/script atomicity does not provide transaction joining across Muse
  processors or XA semantics.

## Rejected Alternatives

Globally registering drivers with `DriverManager` was rejected because it
couples visibility to the connector caller class and can leak child
classloaders.

Loading driver JAR paths inside the connector was rejected because Muse has no
application shutdown hook for disposing connector-owned classloaders, and
closing a loader before every borrowed connection is released is unsafe.

A Spring-only connector was rejected because JDBC and `DataSource` need no
framework adapter. A Spring module can be added later only for a concrete
container-owned capability that cannot be expressed through the public API.

Fake connector-local transaction participation was rejected because it would
hide non-atomic behavior behind Mule-compatible names.
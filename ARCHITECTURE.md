# Muse Architecture

Muse is a Kotlin integration framework inspired by MuleSoft. Applications define
flows with a typed DSL; the engine resolves their components at bootstrap and
executes events with coroutines. Babelfish translates Mule XML into the same
model and emits Kotlin source.

## Module boundaries

The project separates declarative configuration, runtime contracts, runtime
implementation, connector APIs, and framework adapters.

```text
muse-dsl
    ↑
muse-engine-api
    ↑
muse-engine ──→ muse-engine-scala
    ↑
muse-spring-boot
```

### Declarative layer

`muse-dsl` owns:

- public configuration and model types;
- flow, sub-flow, processor, router, and scheduler DSL functions;
- internal builders;
- declarative error handlers and flow references;
- static flow-reference validation;
- `DwExpression` declarations.

It has no dependencies on another Muse module. This is an internal-dependency
constraint, not a claim that the artifact has no external libraries.

### Runtime contract layer

`muse-engine-api` depends on `muse-dsl` and owns contracts that processors,
sources, connectors, and adapters need without depending on the engine
implementation:

- `MuseEvent`, `TypedValue`, and `MuseError`;
- `ExecutionContext`, `MuseProcessor`, `Pipeline`, and `ProcessorResult`;
- resolved error-handler types;
- `MuseSource` and `MuseEventEnvelope`;
- component factory and registrar SPI;
- expression-evaluator contracts;
- infrastructure requirements;
- streaming cursor providers and resource ownership.

### Runtime implementation layer

`muse-engine` depends on `muse-dsl`, `muse-engine-api`, and
`muse-engine-scala`. It owns:

- bootstrap and application lifecycle;
- component resolution and execution;
- built-in processors and routers;
- error-handler execution;
- target handling;
- property loading and resolution;
- DataWeave integration;
- coroutine dispatcher management.

Connector API and runtime modules must not depend on `muse-engine`. A
framework-specific adapter may compose with the engine when it owns the
application/runtime integration boundary.

## Application lifecycle

Muse uses three phases:

```text
Bootstrap → Init → Start
```

### Bootstrap

1. Build the partial application context.
2. Apply global configurations.
3. Resolve and validate flow references.
4. Fail before startup if the configuration graph is invalid.

### Init

1. Create sources for active flows.
2. Resolve processor and router pipelines through the component factory.
3. Associate each source with its channel and resolved pipeline.

### Start

1. Start active sources.
2. Sources register external resources such as HTTP routes or schedules.
3. Events enter the engine through `MuseEventEnvelope`.

Processors return `ProcessorResult`; exceptions are not the normal execution
control mechanism. See
[ADR-001](docs/adr/ADR-001-execution-model-result-type.md) and
[ADR-002](docs/adr/ADR-002-static-resolution-bootstrap.md).

## File connector family

```text
muse-file-connector-api
    ↑                    ↑
muse-file-connector      muse-file-babelfish-mappers
```

- `muse-file-connector-api` owns the public connection configuration, operation
  and listener models, file metadata, matcher/subset types, error hierarchy, and
  typed Kotlin DSL;
- `muse-file-connector` owns local filesystem access, zero-copy cursor streams,
  operation processors, the polling source, and component registration;
- `muse-file-babelfish-mappers` owns Mule File XML mapping and Kotlin DSL emission.

The connector implements the Mule File connector's read, write, list, copy,
move, delete, rename, create-directory, and on-new-or-updated-file contracts.
Relative paths resolve against the configured working directory; absolute paths
remain valid external paths. Runtime code depends only on `muse-dsl`,
`muse-engine-api`, and the File API. Babelfish File mappers depend only on
`muse-babelfish`, `muse-dsl`, and `muse-file-connector-api`.

## HTTP connector family

The HTTP family is an independent reactor rooted at `muse-http/pom.xml`.

```text
muse-http-connector-api
    ↑                    ↑
muse-http-connector      muse-http-connector-testkit
    ↑
muse-http-connector-spring

muse-babelfish + muse-http-connector-api
    ↓
muse-http-babelfish-mappers
```

Responsibilities:

- `muse-http-connector-api`: HTTP configuration, DSL, models, source contract,
  client/server interfaces, and initializer SPI;
- `muse-http-connector`: framework-neutral request processor implementation;
- `muse-http-connector-testkit`: reusable adapter contracts;
- `muse-http-connector-spring`: Spring server/client implementations and
  auto-configuration;
- `muse-http-babelfish-mappers`: Mule HTTP XML mapping and Kotlin emission.

Framework-specific request or response types do not cross the public API
boundary. Server and client implementations are discovered through the HTTP
initializer interfaces. Infrastructure requirements live in `muse-engine-api`.

## SFTP connector family

```text
muse-sftp-connector-api
    ↑
muse-sftp-connector

muse-sftp-connector-testkit

muse-babelfish + muse-sftp-connector-api
    ↓
muse-sftp-babelfish-mappers
```

- `muse-sftp-connector-api` owns connection configuration, typed references,
  operation/source models, file metadata, matchers, error types, and DSL;
- `muse-sftp-connector` owns Apache MINA SSHD integration, processors, lazy
  remote streams, the polling source, and `MuseComponentRegistrar` metadata;
- `muse-sftp-connector-testkit` owns the reusable embedded SFTP server used by
  transport and packaged application tests;
- `muse-sftp-babelfish-mappers` owns Mule SFTP XML mapping and Kotlin emission.

The API depends only on `muse-dsl`. The runtime depends on the API and
`muse-engine-api`, never on the engine implementation or a framework adapter.
Each operation owns and closes its SSH session. A read cursor keeps its session
open only while that cursor is open; the engine streaming registry releases any
cursor not transferred to an outbound adapter.

## Database connector family

```text
muse-db-connector-api
    ↑
muse-db-connector

muse-babelfish + muse-db-connector-api
    ↓
muse-db-babelfish-mappers
```

- the API module owns typed JDBC/DataSource configuration, operation and source
  models, results, errors, and DSL builders;
- the runtime module owns driver isolation, JDBC execution, named parameters,
  row streaming, polling, and ServiceLoader registration;
- the Babelfish mapper module owns Mule Database XML mapping and Kotlin
  emission;
- no Spring adapter is required because JDBC and `DataSource` are
  framework-neutral.

Driver artifacts and explicit driver classloaders remain application-owned.
The runtime instantiates drivers in the selected realm and calls
`Driver.connect` directly without JVM-global registration. See
[ADR-020](docs/adr/ADR-020-database-connector-and-driver-isolation.md).

## APIKit connector family

```text
muse-apikit-connector-api
    ↑
muse-apikit-connector
    ↑
muse-apikit-connector-spring

muse-babelfish + muse-apikit-connector-api
    ↓
muse-apikit-babelfish-mappers
```

- the API module owns public configuration, parser, routing models, and DSL;
- the runtime module owns routing and request validation;
- the Spring module owns framework integration and RAML loading;
- the Babelfish mapper module owns APIKit XML mapping and Kotlin emission.

## Babelfish provider architecture

`muse-babelfish` is provider-neutral. It owns XML parsing, raw models, registry
and collision behavior, mapper/emitter SPI, diagnostics, and code-generation
mechanics.

Concrete component families are independent providers:

| Provider | Module | Public family API |
|---|---|---|
| Core | `muse-babelfish-core-mappers` | Muse DSL and built-in runtime components |
| HTTP | `muse-http-babelfish-mappers` | `muse-http-connector-api` |
| APIKit | `muse-apikit-babelfish-mappers` | `muse-apikit-connector-api` |
| SFTP | `muse-sftp-babelfish-mappers` | `muse-sftp-connector-api` |
| Database | `muse-db-babelfish-mappers` | `muse-db-connector-api` |
| File | `muse-file-babelfish-mappers` | `muse-file-connector-api` |

Each provider packages exactly one
`META-INF/services/dev.museframework.babelfish.spi.BabelfishMapperProvider`
entry. The Maven plugin bundles the core provider; optional providers are made
visible through Maven plugin dependencies.

Provider modules own their semantic verification. `muse-plugin-it` owns
plugin-classloader composition for core, HTTP, APIKit, and Database, including the
`all-built-in-providers` fixture. Family realms
`muse-{file,sftp,db}-plugin-it` own their Invoker fixtures.
`muse-packaged-e2e` owns platform packaging; `muse-{db,file,sftp}-packaged-e2e`
own family packaged acceptance; `muse-connector-composition-e2e` owns bounded
File→SFTP→Database runtime composition. Discovery uses `muse.verification.*`
POM tags and `scripts/verification-owners.py`. See
[ADR-019](docs/adr/ADR-019-babelfish-provider-owned-testing.md).

### Writing and upgrading a provider

Register each Kotlin emitter against the model type returned by its mapper.
The Core provider registers the `SetPayloadConfig` emitter this way.

```kotlin
override fun registerEmitters(emitter: KotlinCodeEmitter, nativeBuild: Boolean) {
    emitter.registerElementEmitter(SetPayloadConfig::class, SetPayloadCodeEmitter)
}
```

`ElementCodeEmitter<T>`, `SourceCodeEmitter<T>`, and `ConfigCodeEmitter<T>`
return KotlinPoet `CodeBlock` in Muse 0.3.0. Upgrade a 0.2.1 provider by returning
structured blocks from these methods. Use `%M` with `MemberName` for DSL calls,
`%T` for type references, and `%N` for generated identifiers. Use `%L` to insert
a child block, including the output of `ctx.emitPipelineElements(...)` from
routers. Use `kotlinStringLiteral(value)` with `%L` for model strings. Keep the
provider ID, service descriptor, and registration entry points. Replace
`CodegenContext.escapeKotlinString` and manual import registration with these
structured references. Babelfish composes the blocks into one KotlinPoet file
and resolves their imports.

Declare a direct `com.squareup:kotlinpoet-jvm` dependency in the provider module.
The Muse BOM manages the version. Recompile and republish third-party provider
JARs against Muse 0.3.0, then upgrade the Maven plugin and its provider
dependencies together. The three emitter return types change the JVM method
descriptors, so a provider built against 0.2.1 can register and then fail when
an emitter is invoked. Put provider dependencies under the plugin's
`<dependencies>` section. Generated applications depend on the Muse DSL and
their runtime and connector libraries.

Run translation and compilation in a Spring Boot Maven project with the plugin's
`generate` goal bound to `generate-sources` and the Kotlin Maven plugin configured
to compile the generated source root.

```bash
mvn -B verify -Dmuse.packageName=org.example.integrations
```

With an input at `src/main/mule/order-API.XML`, translation produces
`target/generated-sources/muse/org/example/integrations/OrderAPIConfig.kt`.
For a one-time migration, run these commands as separate Maven invocations so
the compilation reads the replacement POM.

```bash
mvn -B dev.museframework.maven:muse-maven-plugin:0.3.0-SNAPSHOT:migrate \
  -Dmuse.runtime=spring-boot -Dmuse.packageName=org.example.integrations
mvn -B compile
```

### Generated source and model compatibility

KotlinPoet can change import order, escaping syntax, indentation, numeric
grouping, and line wrapping. Verify the generated Kotlin by compiling it and
checking the resulting Muse model and runtime behavior.

The literal helper preserves the exact string delivered to the emitter,
including LF, CR, CRLF, trailing line breaks, and blank lines. XML parsing
still applies XML line-ending normalization to physical line endings. Use XML
character references such as `&#13;&#10;` where an exact CRLF value is needed
in parsed character data or attributes. Database SQL uses the parsed untrimmed
leaf text. Database parameter expressions continue to use trimmed text.

The Maven translation goals use the allocated Kotlin class name for the source
file and reject generated paths that collide, including paths that differ only
by case, before writing any output. A mapped component without an emitter fails
with a diagnostic identifying the missing model type.

## Maven plugin

```text
muse-plugin-core
    ↑
muse-maven-plugin
    ↑
muse-plugin-it
```

- `muse-plugin-core` detects runtimes, loads Babelfish providers, and translates
  Mule applications;
- `muse-maven-plugin` exposes Maven goals;
- `muse-plugin-it` uses Maven Invoker to verify isolated plugin realms,
  generated sources, optional providers, and failure diagnostics for core, HTTP,
  APIKit, and Database.

`muse-file-plugin-it`, `muse-sftp-plugin-it`, and `muse-db-plugin-it` are root
reactor modules. They depend on `muse-maven-plugin` and use the same Invoker
boundary for their families. They are not modules of `muse-maven-plugin`.

Generated files are written under `target/generated-sources/muse` and are never
edited or committed.

## Spring Boot integration

`muse-spring-boot` composes the DSL and engine into a Spring application. It
supports:

- reflection-based `MuseConfigDefinition` declarations;
- explicit `MuseConfigProvider` declarations for native-friendly applications;
- lifecycle integration;
- component and configuration collection;
- infrastructure handling.

Framework adapters remain outside `muse-dsl` and `muse-engine-api`.

## Error handling

Declarative error handlers live in `muse-dsl`; resolved runtime strategies live
in `muse-engine-api`; execution lives in `muse-engine`.

Resolution order is:

```text
flow handler
→ enclosing execution-context handler
→ global handler
→ default propagate handler
```

A referenced flow carries its own resolved handler. A sub-flow inherits the
caller handler. Processor failures preserve event state according to the
resolved strategy.

## Streaming ownership

Streaming contracts live in `muse-engine-api`. Each root execution context owns
a resource registry. Ownership may transfer explicitly to a source response
adapter while the adapter serializes a reply; all other resources are released
by the engine. See
[ADR-018](docs/adr/ADR-018-repeatable-streaming.md).

## Testing boundaries

- `*Test`: unit behavior and provider contracts;
- `*IT`: module/JVM integration;
- `*E2ETest`: package, process, startup, or real transport boundary;
- Maven Invoker: Maven plugin classloader and generated-project behavior;
- architecture tests: dependency and package constraints.

Test fixtures belong to the module that owns the behavior. Cross-family
compositions are explicit and live at plugin or packaged application boundaries.

### Connector verification ownership

Active owners:

- platform: `muse-packaged-e2e` (no DB/File/SFTP runtimes);
- packaged: `muse-{db,file,sftp}-packaged-e2e`;
- composition: `muse-connector-composition-e2e` — File → SFTP → Database
  success, mid-transfer SFTP failure, and Database failure after remote
  completion (`applyPostActionWhenFailed=false`; no rollback / XA / exactly-once
  promise);
- plugin-realm: `muse-plugin-it` plus `muse-{file,sftp,db}-plugin-it`.

Helpers `muse-packaged-test-parent` and `muse-packaged-e2e-testkit` are shared
support, not owners. Runtime composition is distinct from the central plugin
compatibility set (core, HTTP, APIKit, Database). `-Pe2e` runs both `*IT` and
`*E2ETest`. CI discovers owners via POM tags and runs them through
`scripts/run-verification-owner.py`. See
[ADR-019](docs/adr/ADR-019-babelfish-provider-owned-testing.md).

## Release architecture

The root and HTTP reactors use Sonatype's Central Publishing Maven extension.
The `release` profile attaches sources, Javadoc, and signatures. Fixtures,
examples, architecture tests, packaged E2E modules, and Maven plugin ITs set
`skipPublishing=true`.

Publication remains manual after Central validation (`autoPublish=false`). A tag
must match the project version before the GitHub workflow deploys a bundle.

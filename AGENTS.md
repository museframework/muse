# Muse Repository Guide

## Project

Muse is a multi-module Maven project written primarily in Kotlin. It provides a
typed integration-flow DSL, a coroutine-based runtime, connector APIs and
adapters, Spring Boot integration, and Babelfish Mule XML translation.

Public Maven coordinates use the `dev.museframework` namespace. Libraries and
examples target JDK 21.

## Module boundaries

### Declarative model

`muse-dsl` owns public configuration types, flow models, builders, DSL functions,
and static validation. It has no dependencies on another Muse module.

### Runtime contracts

`muse-engine-api` depends on `muse-dsl` and owns event, processor, source,
execution, expression, infrastructure, SPI, and streaming contracts.

### Runtime implementation

`muse-engine` owns bootstrap, execution, built-in components, error handling,
properties, dispatchers, and DataWeave integration. It depends on `muse-dsl`,
`muse-engine-api`, and `muse-engine-scala`.

Connector APIs and framework-neutral connector runtimes must not depend on
`muse-engine`. Framework adapters may compose with the engine at the application
integration boundary.

### HTTP family

The HTTP reactor is rooted at `muse-http/pom.xml`:

- `muse-http-connector-api`: public HTTP configuration, DSL, models, and SPI;
- `muse-http-connector`: framework-neutral processor runtime;
- `muse-http-connector-testkit`: reusable adapter contracts;
- `muse-http-connector-spring`: Spring server/client adapters;
- `muse-http-babelfish-mappers`: HTTP Mule XML mapper provider.

### SFTP family

- `muse-sftp-connector-api`: public SFTP configuration, models, and typed DSL;
- `muse-sftp-connector`: Apache MINA SSHD runtime, processors, polling source;
- `muse-sftp-connector-testkit`: reusable embedded SFTP server fixture;
- `muse-sftp-babelfish-mappers`: SFTP Mule XML mapper provider.

### File family

- `muse-file-connector-api`: public File configuration, models, and typed DSL;
- `muse-file-connector`: local filesystem operations, streaming, and polling source;
- `muse-file-babelfish-mappers`: File Mule XML mapper provider.

### Quarkus family

- `musek-quarkus`: core Quarkus extension, split into runtime and deployment modules;
- `musek-http-connector-quarkus`: Vert.x HTTP adapter, split into runtime and deployment modules;
- `test-musek-quarkus`: Quarkus application and transport fixture.

Quarkus runtime modules own CDI and Vert.x integration. Deployment modules own
build steps and extension metadata; these framework types must not leak into
the declarative model, Engine API, or framework-neutral connector runtime.

### APIKit family

- `muse-apikit-connector-api`: public APIKit configuration and routing model;
- `muse-apikit-connector`: router and validation runtime;
- `muse-apikit-connector-spring`: Spring and RAML integration;
- `muse-apikit-babelfish-mappers`: APIKit Mule XML mapper provider.

### Babelfish

`muse-babelfish` is provider-neutral. Concrete provider modules own their
mappers, emitters, service descriptors, fixtures, and semantic integration
tests:

- core: `muse-babelfish-core-mappers`;
- HTTP: `muse-http-babelfish-mappers`;
- APIKit: `muse-apikit-babelfish-mappers`;
- SFTP: `muse-sftp-babelfish-mappers`;
- Database: `muse-db-babelfish-mappers`;
- File: `muse-file-babelfish-mappers`.

Do not add a central provider integration-test module. Maven plugin classloader
composition for core, HTTP, APIKit, and Database belongs to `muse-plugin-it`.
Family plugin realms (`muse-{file,sftp,db}-plugin-it`) own their Invoker
fixtures. Platform packaged acceptance belongs to `muse-packaged-e2e`; DB/File/SFTP
packaged acceptance and File→SFTP→Database composition belong to their family and
composition modules. See
`docs/adr/ADR-019-babelfish-provider-owned-testing.md`.

### Maven plugin

- `muse-plugin-core`: runtime detection and provider-backed translation;
- `muse-maven-plugin`: Maven goals;
- `muse-plugin-it`: isolated Maven Invoker fixtures for core, HTTP, APIKit, and Database.

`muse-file-plugin-it`, `muse-sftp-plugin-it`, and `muse-db-plugin-it` are root
reactor modules. They depend on `muse-maven-plugin` and own family provider
fixtures. They are not modules of `muse-maven-plugin`.

Generated source under `target/generated-sources/muse` is disposable and must
never be edited.

## Dependency rules

1. `muse-dsl` has no internal Muse dependencies.
2. `muse-engine-api` may depend on `muse-dsl`, never on `muse-engine`.
3. Connector API/runtime modules may depend on stable DSL and Engine API
   contracts, never the engine implementation.
4. Babelfish providers depend on `muse-babelfish`, `muse-dsl`, and their
   component family's public API. They do not depend on the family runtime.
5. Spring-specific types remain in Spring adapter modules.
6. Public APIs use typed references; do not introduce stringly typed flow or
   configuration references.

Architecture tests enforce the major package and dependency boundaries.

## Component lifecycle

Muse starts in three phases:

```text
Bootstrap → Init → Start
```

### Bootstrap

- create the partial context;
- apply global configurations;
- resolve and validate flow references;
- fail before startup on invalid configuration.

### Init

- create sources for active flows;
- resolve processor and router pipelines;
- associate sources, channels, and pipelines.

### Start

- start active sources;
- register external routes or schedules;
- accept events through `MuseEventEnvelope`.

Global configuration may initialize shared resources during bootstrap.
Components consume that state through the application context during init.

## Execution rules

- Processors return `ProcessorResult`; exceptions are not normal flow control.
- Flow references are resolved at bootstrap.
- A referenced flow carries its resolved error handler; sub-flows inherit the
  caller handler.
- Event correlation IDs are first-class and propagate through child contexts.
- Streaming resources have explicit registry and response-adapter ownership.
- Failures must preserve event mutations according to the resolved error
  strategy.

Relevant decisions live under `docs/adr/`.

## Kotlin conventions

- Kotlin official code style.
- Public configuration/model classes; internal builders where possible.
- JUnit 5 test names use backticks for behavior descriptions.
- MockK is the default mocking library.
- Avoid avoidable allocation, copying, and payload materialization.
- Framework-specific types must not leak through public connector APIs.

Typical DSL component shape:

```text
public config/model
→ internal builder
→ public DSL function
→ runtime processor/router/source
→ component registrar entry
```

## Test ownership

- `*Test`: unit behavior and provider contracts;
- `*IT`: integration within one module/JVM;
- `*E2ETest`: package, process, application startup, or real transport;
- Maven Invoker: plugin classloader and generated project behavior.

Tests must defend observable contracts. Fixture ownership follows production
ownership. Do not use unrelated connectors as fixture scaffolding.

Every Babelfish provider must have:

- focused mapper and emitter unit tests;
- `<Family>MapperProviderTest`;
- one independent `<Family>TranslationIT` fixture;
- non-empty Surefire and Failsafe reports;
- Maven Invoker coverage when consumed by code generation.

### Connector verification ownership

Active owners (discovered via `muse.verification.*` POM tags):

| Kind | Modules |
|------|---------|
| platform | `muse-packaged-e2e` |
| packaged | `muse-{db,file,sftp}-packaged-e2e` |
| composition | `muse-connector-composition-e2e` |
| plugin-realm | `muse-plugin-it`, `muse-{file,sftp,db}-plugin-it` |

Helpers `muse-packaged-test-parent` and `muse-packaged-e2e-testkit` are shared
support, not owners. Tags: `muse.verification.kind`, `muse.verification.family`,
optional `muse.verification.codegen=true` for generation-enabled owners.

Runtime composition covers File→SFTP→Database success, mid-transfer SFTP
failure, and Database failure after remote completion with
`applyPostActionWhenFailed=false`. It does not promise rollback, XA, exactly-once
delivery, or all-database-vendor compatibility. That contract is distinct from
the central plugin compatibility set (core/HTTP/APIKit/Database). File and SFTP
realm Invoker suites stay family-local.

SFTP/File generated-compilation coverage is separate from handwritten runtime
E2E. File packaged acceptance is headless (no HTTP transport). Composition uses
a bounded-heap streaming oracle for transfer evidence; that oracle's scope is
the composition module, not a general performance guarantee. `-Pe2e` runs both
`*IT` and `*E2ETest`; report gates
(`scripts/assert-e2e-reports.py` / `scripts/assert-invoker-reports.py`) require
non-empty suite inventories. New families add tagged reactor modules once, keep
family runtimes out of `muse-packaged-e2e`, and prove discovery with
`scripts/verification-owners.py --require-family-pairs`. See
`docs/adr/ADR-019-babelfish-provider-owned-testing.md`.

## Common commands

Complete unit-test reactor:

```bash
mvn clean test -Dkotlin.daemon.enabled=false
```

Normal verification reactor:

```bash
mvn clean verify -Dkotlin.daemon.enabled=false
```

Quarkus test application:

```bash
mvn -f test-musek-quarkus/pom.xml test \
  -Dkotlin.daemon.enabled=false
```

Babelfish core, APIKit, SFTP, File, and Database providers:

```bash
mvn verify \
  -Dkotlin.daemon.enabled=false \
  -pl=muse-babelfish,muse-babelfish-core-mappers,muse-apikit-babelfish-mappers,muse-sftp-babelfish-mappers,muse-file-babelfish-mappers,muse-db-babelfish-mappers \
  -am
```

HTTP Babelfish provider:

```bash
mvn -f muse-http/pom.xml verify \
  -Dkotlin.daemon.enabled=false \
  -pl :muse-http-babelfish-mappers \
  -am
```

Maven plugin Invoker fixtures:

```bash
mvn clean verify --batch-mode \
  -DskipTests \
  -Dkotlin.daemon.enabled=false \
  -Pplugin-it \
  -pl muse-maven-plugin/muse-plugin-it \
  -am
```

File provider realm fixtures:

```bash
mvn clean verify --batch-mode \
  -DskipTests \
  -Dkotlin.daemon.enabled=false \
  -Pplugin-it \
  -pl muse-file-plugin-it \
  -am
```

SFTP / Database provider realm fixtures use the same Invoker profile with
`-pl muse-sftp-plugin-it` or `-pl muse-db-plugin-it`.

Packaged / composition acceptance (combined IT + E2E under `-Pe2e`):

```bash
# one owner, e.g. platform
mvn clean install --batch-mode \
  -DskipTests \
  -Dkotlin.daemon.enabled=false \
  -pl :muse-packaged-e2e \
  -am

mvn clean verify --batch-mode \
  -Dkotlin.daemon.enabled=false \
  -Pe2e \
  -pl :muse-packaged-e2e
```

Substitute `:muse-db-packaged-e2e`, `:muse-file-packaged-e2e`,
`:muse-sftp-packaged-e2e`, or `:muse-connector-composition-e2e` for family or
composition owners. Discover owners and run one through the generic preparer:

```bash
python3 scripts/verification-owners.py . --format json --require-family-pairs
python3 scripts/run-verification-owner.py muse-packaged-e2e \
  --repository /tmp/muse-repo-platform \
  --scratch /tmp/muse-scratch-platform
python3 scripts/assert-e2e-reports.py muse-packaged-e2e \
  muse-packaged-e2e/target/failsafe-reports \
  --suite-suffix IT --suite-suffix E2ETest
```

Release artifact verification without a real signature:

```bash
mvn clean verify \
  -Prelease \
  -DskipTests \
  -Dgpg.skip=true \
  -Dkotlin.daemon.enabled=false
```

## Release rules

- Release versions must not use `-SNAPSHOT`.
- The Git tag must match the Maven project version.
- Publishable JARs require source, Javadoc, and PGP signature artifacts.
- Scala documentation uses `scala-maven-plugin:doc-jar`.
- Fixtures, examples, architecture tests, packaged E2E, and plugin IT modules
  must set `skipPublishing=true`.
- Central validation requires explicit manual publication because
  `autoPublish=false`.

## Repository hygiene

Commit only source, build configuration, CI, current documentation, synthetic
fixtures, and intentionally shared IDE settings.

Never commit:

- `target/`, `build/`, or `out/`;
- generated sources;
- logs;
- credentials or real customer material;
- local agent state;
- user-specific IDE state.

Shared IntelliJ files may include code styles, encodings, compiler settings, and
VCS mappings. Workspace, tasks, generated Maven import state, data sources, and
plugin-local mappings remain untracked.

Before committing:

```bash
git diff --check
git status --short
```

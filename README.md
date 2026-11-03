<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/muse-mark-dark.svg">
    <img src="docs/assets/muse-mark.svg" alt="Muse logo" width="140">
  </picture>

  <h1>Muse</h1>
  <p><strong>Build integration flows. The Kotlin way.</strong></p>
  <p>
    Typed Kotlin DSL, coroutine-powered execution, and Babelfish translation
    from Mule XML to Kotlin source.
  </p>

  <p>
    <a href="#getting-started">Getting started</a> ·
    <a href="#connectors-and-hosts">Connectors</a> ·
    <a href="#build">Build</a> ·
    <a href="ARCHITECTURE.md">Architecture</a>
  </p>

  <p>
    <a href="https://github.com/museframework/muse/actions/workflows/maven.yml"><img alt="Maven CI" src="https://github.com/museframework/muse/actions/workflows/maven.yml/badge.svg?branch=main"></a>
    <a href="https://central.sonatype.com/artifact/dev.museframework/muse-bom"><img alt="Maven Central version" src="https://img.shields.io/maven-central/v/dev.museframework/muse-bom?label=Maven%20Central&amp;style=flat-square"></a>
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&amp;logoColor=white&amp;style=flat-square">
    <img alt="JDK 21" src="https://img.shields.io/badge/JDK-21-blue?style=flat-square">
    <a href="LICENSE.md"><img alt="Sustainable Use License" src="https://img.shields.io/badge/License-Sustainable%20Use-0D1926?style=flat-square"></a>
  </p>
</div>

## Requirements

- JDK 21
- Maven 3.9+
- Kotlin dependencies use versions managed by `muse-bom`

## Getting started

Muse flows use a typed Kotlin DSL. You can write Kotlin flows directly or use Babelfish to translate supported Mule XML components into Kotlin source.

### Write a Kotlin flow

This flow replaces the event payload with a string:

```kotlin
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload

val flow = flow("set-payload-literal-flow") {
    setPayload {
        docName = "Set Payload"
        value = "new-payload-value"
    }
}
```

The [integration test](muse-engine/src/test/kotlin/dev/museframework/engine/integration/SetPayloadIT.kt) executes this definition and checks that the resulting payload is `new-payload-value`. A flow also needs a host and an event source to receive requests.

### Run the Spring Boot example

The [Spring Boot example](examples/test-muse-spring-boot/) has its own external Spring Boot Maven parent. It defines an HTTP listener at `/api/hello`. From the repository root:

```bash
cd examples/test-muse-spring-boot
mvn -B -ntp -Dkotlin.daemon.enabled=false -Pe2e verify
mvn -B -ntp -Dkotlin.daemon.enabled=false spring-boot:run
```

With the application running, send a request from another terminal:

```bash
curl -i http://localhost:8080/api/hello
```

Expected: HTTP `200`, `Content-Type: text/plain`, and the body `"hello world"` (including the quotation marks). The example also contains scheduled/background flows; network access may be used by those flows. A clean-cache, isolated-settings run of this example on JDK 21 and Maven 3.9.16 completed nine tests successfully on 2026-11-03, and the endpoint response above was observed. These commands were not rerun as part of this README edit.

The [Quarkus example](examples/test-muse-quarkus/) also uses an external parent. It is available for source inspection, but its Muse Quarkus artifacts failed public dependency resolution in the isolated promotion check; it is not presented here as a verified standalone onboarding path.

### Migrate Mule XML

Configure the Muse Maven plugin, a runtime and the mapper providers required by your application. Put Mule XML in `src/main/mule` (the default input directory). In the reviewed Spring Boot migration example, the working path was:

```bash
mvn muse:generate
mvn compile
mvn muse:run -Dmuse.skipGenerate=true
```

This sequence generated Kotlin directly, compiled it and started the application. On that example, `mvn muse:check compile` reported 17 nested XML elements as unmapped even though direct generation and compilation succeeded. A plain `mvn muse:run` failed during delegated generation with mapper-resolution errors. The cause of that delegation failure has not been established; `-Dmuse.skipGenerate=true` skips the failing regeneration only after successful direct generation.

This is a bounded workaround for the tested Spring application, not a guarantee for other Mule XML. Compare successful responses, errors and external effects with the original application before cutover. The reviewed example also returned unexpected statuses for two negative HTTP requests. The [Maven plugin guide](docs/maven-plugin.md) records those limits.

In this XML-first workflow, keep editing the XML and application configuration. Files under `target/generated-sources/muse` are regenerated and should not be edited. Mapping coverage does not establish full MuleSoft compatibility. See [Maven plugin runtime targets](docs/maven-plugin.md) for the setup and runtime-specific limitations.

## Connectors and hosts

Connector families have a public API and a runtime implementation. Babelfish providers translate supported Mule XML components for each family.

| Family | Purpose | Source |
|---|---|---|
| HTTP | Client requests and HTTP listeners | [HTTP guide](docs/connectors/http.md) |
| File | Local file operations and polling | [File guide](docs/connectors/file.md) |
| SFTP | Remote file operations and polling | [SFTP guide](docs/connectors/sftp.md) |
| Database | JDBC operations and polling | [Database guide](docs/connectors/database.md) |
| APIKit | API routing and request validation | [APIKit guide](docs/connectors/apikit.md) |

Muse can run in Spring Boot or Quarkus applications. The [architecture guide](ARCHITECTURE.md) explains the separation between the DSL, engine, connectors and host adapters. The engine also integrates DataWeave through its Scala bridge.

The APIKit Quarkus extension and application tests exist. The Maven plugin still rejects **Mule XML APIKit migration targeting Quarkus**. Support for a handwritten APIKit Quarkus application does not imply that the migration path is available.

## Modules

The project is a multi-module Maven build. `muse-dsl` defines the model, `muse-engine-api` defines runtime contracts and `muse-engine` executes flows. Connector modules, host adapters and Babelfish providers have separate responsibilities described in [ARCHITECTURE.md](ARCHITECTURE.md).

## Maven plugin runtime targets

The plugin supports Spring Boot and Quarkus runtime selection through `muse.runtime`, with detection from project dependencies when the property is omitted. See [the Maven plugin guide](docs/maven-plugin.md) for commands, generated project structure and compatibility limits.

## SFTP connector

The [SFTP guide](docs/connectors/sftp.md) covers dependency coordinates, Kotlin DSL usage, strict host-key checking, streaming operations and listener polling.

## Build

From the repository root, run the core reactor's unit and normal integration test suites:

```bash
mvn clean test -Dkotlin.daemon.enabled=false
mvn clean verify -Dkotlin.daemon.enabled=false
```

The HTTP family can be built through its own `muse-http/pom.xml` reactor; the root `pom.xml` also aggregates it. Contributor commands for HTTP, Babelfish, Maven Invoker, packaged E2E and optional suites are in the [verification guide](docs/development/verification.md).

## Babelfish verification

Babelfish providers own their semantic tests. [ADR-019](docs/adr/ADR-019-babelfish-provider-owned-testing.md) explains the test boundaries, and the [verification guide](docs/development/verification.md) gives the exact commands.

## Documentation

- [Architecture](ARCHITECTURE.md) and [repository guide](AGENTS.md)
- [HTTP](docs/connectors/http.md), [File](docs/connectors/file.md), [SFTP](docs/connectors/sftp.md), [Database](docs/connectors/database.md), and [APIKit](docs/connectors/apikit.md) guides
- [HTTP listener](docs/http-listener-guide.md), [streaming](docs/streaming-guide.md), and [Maven plugin](docs/maven-plugin.md) guides
- [Contributor verification](docs/development/verification.md)
- [Architecture decisions](docs/adr/)

## License

Muse is distributed under the terms in [LICENSE.md](LICENSE.md).

---

<p align="center">
  Built by <a href="https://github.com/giacomobartoloni">Giacomo Bartoloni</a>
  · <a href="https://www.linkedin.com/in/giacomobartoloni/">LinkedIn</a>
  · <a href="https://github.com/museframework">Muse Framework</a>
</p>

# Muse

Muse is a Kotlin integration framework inspired by MuleSoft. It provides a typed
DSL for defining flows, a coroutine-based execution engine, HTTP, File, and
APIKit connectors, Spring Boot integration, and Babelfish translation from Mule
XML to Kotlin source.

## Requirements

- JDK 21
- Maven 3.9+
- Kotlin compilation uses the versions managed by `muse-bom`

## Build

Run the complete unit-test reactor:

```bash
mvn clean test -Dkotlin.daemon.enabled=false
```

Run all normal integration tests:

```bash
mvn clean verify -Dkotlin.daemon.enabled=false
```

## Modules

Muse is organized by families, not by a full artifact inventory:

| Family | Role |
|---|---|
| Core | DSL → Engine API → Engine (+ Scala bridge for DataWeave) |
| Adapters | Spring Boot and Quarkus application integration |
| Connectors | HTTP and APIKit (public API, runtime, framework adapters, Babelfish providers) |
| Tooling | Babelfish, Maven plugin, packaged E2E |

Dependency rules, lifecycle, and contributor guidance live in
[ARCHITECTURE.md](ARCHITECTURE.md) and [AGENTS.md](AGENTS.md).

## SFTP connector

Add the public DSL and runtime artifacts:

```xml
<dependency>
    <groupId>dev.museframework</groupId>
    <artifactId>muse-sftp-connector-api</artifactId>
</dependency>
<dependency>
    <groupId>dev.museframework.connectors</groupId>
    <artifactId>muse-sftp-connector</artifactId>
</dependency>
```

The runtime is discovered through `MuseComponentRegistrar`. Host-key checking
is strict by default and reads `~/.ssh/known_hosts`; `INSECURE` must be selected
explicitly and should only be used for disposable local test servers.

```kotlin
val warehouseSftp = sftpConnection {
    name = "warehouse"
    host = "sftp.example.com"
    username = "muse"
    password = System.getenv("SFTP_PASSWORD")
    knownHostsFile = "/etc/muse/known_hosts"
    workingDirectory = "/exchange"
}

val archiveFlow = flow("archive-orders") {
    sftpWrite(warehouseSftp) {
        path = "/outbound/orders.csv"
        mode = SftpWriteMode.CREATE_NEW
    }
    sftpMove(warehouseSftp) {
        sourcePath = "/outbound/orders.csv"
        targetDirectory = "/archive"
        renameTo = "orders-processed.csv"
    }
}
```

The DSL provides `sftpList`, `sftpRead`, `sftpWrite`, `sftpCopy`, `sftpMove`,
`sftpDelete`, `sftpRename`, and `sftpCreateDirectory`. Read payloads are lazy,
repeatable remote cursor providers managed by the engine resource registry.
Paths and returned attributes are relative to `workingDirectory`; traversal
outside it is rejected.
Copy, move, and rename reject a symbolic link at the requested source path or
in either path's parent directories before modifying remote entries. Overwrite
can replace a final destination link.

Use `sftpListener` as a flow source for polling. Its matcher combines glob,
file-type, size, and modification-time criteria. Watermarking is enabled by
default; optional post-processing can delete a processed file or move it to a
different directory.

## Maven plugin runtime targets

The Maven plugin resolves runtime-specific generation and commands through a
runtime strategy. Set `muse.runtime` to `spring-boot` or `quarkus`; when it is
omitted, the plugin detects the runtime from compile/runtime dependencies.

| Runtime | Generated configuration | `muse:run` | `muse:build` | `muse:build-image` |
|---|---|---|---|---|
| Spring Boot | Spring component plus application entry point | `spring-boot:run` | `spring-boot:repackage` | `spring-boot:build-image` |
| Quarkus | CDI bean; native builds use `MuseConfigProvider` | `quarkus:dev` | `quarkus:build` | `quarkus:image-build` |

`muse:migrate` defaults to Spring Boot when no runtime can be detected. Select
Quarkus explicitly with:

```bash
mvn muse:migrate -Dmuse.runtime=quarkus
```

The Quarkus target generates a Quarkus POM, CDI configuration classes,
`application.properties`, native profile, and Jib container-image dependency.
It expects the `muse-quarkus` runtime extension and, for HTTP applications, the
`muse-http-connector-quarkus` extension. APIKit migration currently fails
explicitly for Quarkus because no APIKit Quarkus adapter exists.

## Babelfish verification

Babelfish semantic tests belong to their provider modules:

- core: `muse-babelfish-core-mappers`;
- HTTP: `muse-http-babelfish-mappers`;
- APIKit: `muse-apikit-babelfish-mappers`;
- SFTP: `muse-sftp-babelfish-mappers`;
- Database: `muse-db-babelfish-mappers`;
- File: `muse-file-babelfish-mappers`.

Maven Invoker suites verify provider composition in a real plugin realm.
Core, HTTP, APIKit, and Database fixtures live in `muse-plugin-it`. File, SFTP,
and Database family realms live in `muse-{file,sftp,db}-plugin-it`. Platform
packaged acceptance lives in `muse-packaged-e2e`; family packaged acceptance in
`muse-{db,file,sftp}-packaged-e2e`; bounded File→SFTP→Database composition in
`muse-connector-composition-e2e`. See
[ADR-019](docs/adr/ADR-019-babelfish-provider-owned-testing.md).

Run provider-neutral, core, APIKit, SFTP, File, and Database verification:

```bash
mvn verify \
  -Dkotlin.daemon.enabled=false \
  -pl muse-babelfish,muse-babelfish-core-mappers,muse-apikit-babelfish-mappers,muse-sftp-babelfish-mappers,muse-file-babelfish-mappers,muse-db-babelfish-mappers \
  -am
```

Run the HTTP provider in its owning reactor:

```bash
mvn -f muse-http/pom.xml verify \
  -Dkotlin.daemon.enabled=false \
  -pl :muse-http-babelfish-mappers \
  -am
```

Run the core, HTTP, APIKit, and Database plugin fixtures:

```bash
mvn clean verify --batch-mode \
  -DskipTests \
  -Dkotlin.daemon.enabled=false \
  -Pplugin-it \
  -pl muse-maven-plugin/muse-plugin-it \
  -am
```

Run a family provider realm (File shown; use `muse-sftp-plugin-it` or
`muse-db-plugin-it` the same way):

```bash
mvn clean verify --batch-mode \
  -DskipTests \
  -Dkotlin.daemon.enabled=false \
  -Pplugin-it \
  -pl muse-file-plugin-it \
  -am
```

## Packaged and composition acceptance

`-Pe2e` runs both compilation `*IT` and `*E2ETest` suites. Install required
artifacts, then verify one owner (platform shown; substitute family or
composition artifactIds):

```bash
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

Discover all verification owners and run one through the generic preparer:

```bash
python3 scripts/verification-owners.py . --format json --require-family-pairs
python3 scripts/run-verification-owner.py muse-packaged-e2e \
  --repository /tmp/muse-repo-platform \
  --scratch /tmp/muse-scratch-platform
```

## Optional suites

Engine fuzz tests:

```bash
mvn clean verify --batch-mode \
  -Dkotlin.daemon.enabled=false \
  -Pfuzz \
  -pl muse-engine \
  -am
```

Spring HTTP fuzz tests:

```bash
mvn -f muse-http/pom.xml clean verify --batch-mode \
  -Dkotlin.daemon.enabled=false \
  -Pfuzz \
  -pl :muse-http-connector-spring \
  -am
```

Native-image verification requires GraalVM 21 and is intentionally opt-in:

```bash
mvn -f examples/test-muse-spring-boot-native/pom.xml \
  clean package --batch-mode -Pnative
```

## Repository hygiene

Before merging, inspect the complete diff and confirm that no generated output
is tracked:

```bash
git diff --check
git ls-files | grep -E '(^|/)(target|build|out)/'
git status --short
```

The tracked-artifact command must produce no output. Generated Kotlin under
`target/generated-sources/muse` is disposable and must never be edited or
committed.

## License

Muse is distributed under the terms in [LICENSE.md](LICENSE.md).

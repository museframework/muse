# Contributor verification

This guide contains the project-specific test commands and verification ownership rules. The [repository guide](../../AGENTS.md) defines the general contributor conventions; [ADR-019](../adr/ADR-019-babelfish-provider-owned-testing.md) governs Babelfish provider test ownership.

The root Maven reactor and the independent HTTP reactor have different entry points. Run the commands from the repository root unless a different working directory is shown.

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
[ADR-019](../adr/ADR-019-babelfish-provider-owned-testing.md).

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

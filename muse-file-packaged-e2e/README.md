# File packaged acceptance

This owner runs generated and handwritten File flows in a headless Spring Boot
JAR, in a child JVM. It has no HTTP, SFTP, or database runtime dependency.

## CSV streaming acceptance

`src/main/mule/csv-streaming.xml` is translated by the Maven `generate` goal.
Its foreach collection maps CSV records through a DataWeave expression before
iteration, exercising the stock split backend beyond direct `payload` access.
`PackagedCsvStreamingE2ETest` starts a fresh packaged JVM for each scenario with
`-Xmx128m`. The parent publishes a command file only after startup and input
preparation. These tests run on Linux and macOS, where open-file inspection is
available.

| Scenario | Observable contract |
|---|---|
| Large CSV | 65,536 padded rows, over 256 MiB of input, complete with 128 MiB maximum heap; exact count and identifier checksum. |
| Progressive input | First row enters the body and pauses on a release-file gate; the parent changes the last identifier before releasing it. The flow must report conversion failure after exactly 9,999 valid rows and their checksum. |
| Invalid final record | A non-numeric identifier after 10,000 valid rows produces `MULE:EXPRESSION`, preserving their exact count and checksum. |
| Body failure | An injected `APP:BODY_FAILURE` at row 101 preserves the first 100 rows and stops iteration. |

All scenarios verify that the CSV file descriptor is released while the JVM is
still alive. The progressive scenario also checks that the file is open at the
gate, validating the descriptor oracle. Paths are canonicalized for macOS.
The child must stop gracefully; logs survive under `target/packaged-e2e/`.

The invalid-record test exercises numeric conversion, not CSV syntax rejection.
The DataWeave reader accepts the malformed quoting cases investigated during
development. Reader exceptions and cancellation cleanup are separately covered
by engine contract tests. This suite proves Muse behavior; native Mule semantic
equivalence requires running the XML with matching Mule/connector versions.

## Run

```bash
mvn clean install --batch-mode -DskipTests -Dkotlin.daemon.enabled=false \
  -pl :muse-file-packaged-e2e -am
mvn clean verify --batch-mode -Dkotlin.daemon.enabled=false -Pe2e \
  -pl :muse-file-packaged-e2e
python3 scripts/assert-e2e-reports.py muse-file-packaged-e2e \
  muse-file-packaged-e2e/target/failsafe-reports \
  --suite-suffix IT --suite-suffix E2ETest
```

The tests use the normal packaged artifact by default. For an isolated oracle
check, `-Dpackaged.csv.jar=/absolute/path/to/scratch.jar` overrides only the CSV
tests' child artifact. A scratch artifact with the lazy iteration adapter
disabled must fail the heap/progressive scenarios. Do not replace installed
artifacts or edit generated sources to perform this check.

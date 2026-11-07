# File connector

The File connector reads and writes local files, lists directories, performs file operations and polls directories for new or updated files.

## Maven dependencies

With `dev.museframework:muse-bom:0.1.0` imported in `dependencyManagement`, add the public API and runtime:

```xml
<dependency>
    <groupId>dev.museframework</groupId>
    <artifactId>muse-file-connector-api</artifactId>
</dependency>
<dependency>
    <groupId>dev.museframework.connectors</groupId>
    <artifactId>muse-file-connector</artifactId>
</dependency>
```

The host application also needs the Muse engine integration. The runtime registers its components through the Muse component SPI.

## Configuration and example

`fileConnection` sets the working directory. The following flow polls incoming files, writes the event payload to an output file, and moves the input to the archive after processing:

```kotlin
import dev.museframework.dsl.dsl.flow
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileListener
import dev.museframework.file.api.dsl.fileWrite

val localFiles = fileConnection {
    name = "local"
    workingDirectory = "/srv/muse/files"
}

val archiveFile = flow("archive-file") {
    fileListener(localFiles) {
        directory = "incoming"
        moveToDirectory = "archive"
        applyPostActionWhenFailed = false
    }
    fileWrite(localFiles) {
        path = "output/copied.txt"
    }
}
```

For an incoming `incoming/input.txt` containing `hello`, the intended result is `output/copied.txt` containing `hello` and the input moved into `archive/`. Prepare the working directory and permissions before starting the host. This excerpt is not a standalone application and has not been executed separately.

`applyPostActionWhenFailed` defaults to `true` in the File listener DSL. Set it to `false` when a failed downstream processor must leave the input in place. The listener's watermark mode defaults to `DISABLED`.

## Operations and verification

The typed DSL includes `fileRead`, `fileWrite`, `fileList`, `fileCopy`, `fileMove`, `fileDelete`, `fileRename`, `fileCreateDirectory`, and `fileListener`. File writes support `OVERWRITE`, `APPEND`, and `CREATE_NEW`.

- [File read/write integration tests](../../muse-file-connector/src/test/kotlin/dev/museframework/connectors/file/FileReadWriteIT.kt) exercise streaming through the engine, append, overwrite, create-new behavior, and failures.
- [Packaged File E2E](../../muse-file-packaged-e2e/src/test/kotlin/dev/museframework/e2e/file/PackagedFileConnectorE2ETest.kt) verifies file polling, output, and archive behavior across an application process.
- [File API models](../../muse-file-connector-api/src/main/kotlin/dev/museframework/file/api/model/FileModels.kt) define matchers, watermark settings and modes.

Paths are resolved against the configured working directory when relative. Absolute paths can address external paths, so enforce filesystem permissions and sandbox boundaries in your application. The packaged E2E proves a bounded file journey; it does not establish behavior for every filesystem.

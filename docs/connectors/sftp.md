# SFTP connector

The SFTP connector provides operations for listing, reading, writing, copying, moving, and deleting remote files. It also has a polling listener. The public DSL and runtime are separate Maven artifacts.


With `dev.museframework:muse-bom:0.1.0` imported in `dependencyManagement`, add the public DSL and runtime artifacts:

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

## Using the example

The `archive-orders` flow writes the incoming event payload to a new file, then moves it into the archive directory. Set `SFTP_PASSWORD`, supply the host key in `/etc/muse/known_hosts`, and configure the flow in an application host. This code is a flow definition, not a standalone executable application. A missing payload causes `sftpWrite` to fail.

For an incoming flow payload containing `orders-data`, the example writes `/exchange/outbound/orders.csv` and then moves it to `/exchange/archive/orders-processed.csv`. This is the intended result when the server is reachable, the destination does not already conflict with `CREATE_NEW`, and the configured account can write both directories. The example is not a separate execution result.

## Verification and limitations

- [SFTP flow integration tests](../../muse-sftp-connector/src/test/kotlin/dev/museframework/connectors/sftp/SftpFlowIT.kt) exercise the connector within an application flow.
- [SFTP client integration tests](../../muse-sftp-connector/src/test/kotlin/dev/museframework/connectors/sftp/client/ApacheMinaSftpClientIT.kt) exercise remote file operations.
- [SFTP listener transport tests](../../muse-sftp-connector/src/test/kotlin/dev/museframework/connectors/sftp/source/SftpListenerTransportIT.kt) exercise polling and delivery behavior.

Connection and listener contracts live in [the SFTP API module](../../muse-sftp-connector-api/). Runtime implementation lives in [the SFTP runtime module](../../muse-sftp-connector/).

Host-key verification is strict by default. Provision a trusted host key before using a real server. File post-actions and overwrite behavior depend on the configured operation; there is no claim of distributed transactions or exactly-once delivery.

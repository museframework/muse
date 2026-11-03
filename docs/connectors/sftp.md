# SFTP connector

The SFTP connector provides operations for listing, reading, writing, copying, moving, and deleting remote files. It also has a polling listener. The public DSL and runtime are separate Maven artifacts.


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

### Using the example

The `archive-orders` flow writes the incoming event payload to a new file, then moves it into the archive directory. Set `SFTP_PASSWORD`, supply the host key in `/etc/muse/known_hosts`, and configure the flow in an application host. This code is a flow definition, not a standalone executable application. A missing payload causes `sftpWrite` to fail.

Connection and listener contracts live in [the SFTP API module](../../muse-sftp-connector-api/). Runtime implementation and transport tests live in [the SFTP runtime module](../../muse-sftp-connector/).

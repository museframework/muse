package dev.museframework.e2e.composition

import dev.museframework.e2e.testkit.PackagedApplication
import dev.museframework.e2e.testkit.ProcessSpec
import dev.museframework.e2e.testkit.StopResult
import dev.museframework.sftp.testkit.ControllableSftpServer
import dev.museframework.sftp.testkit.EmbeddedSftpServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

data class PublishedInput(
    val path: Path,
    val sizeBytes: Long,
    val sha256: String,
)

class CompositionFixture private constructor(
    private val root: Path,
    private val applicationJar: Path,
    private val applicationLog: Path,
    val application: PackagedApplication,
    val sftp: ControllableSftpServer,
    val database: H2AuditDatabase,
    val incoming: Path,
    val archive: Path,
    private val probeClient: CompositionProbeClient,
    private val sftpBaselineHandles: Int,
    private val jdbcConnectionBaseline: Int,
) : AutoCloseable {
    val sftpOpenHandleBaseline: Int
        get() = sftpBaselineHandles

    fun publishInput(
        fileName: String,
        sizeBytes: Long,
    ): PublishedInput {
        require(!fileName.contains("..")) { "Path traversal is rejected: $fileName" }
        require(!fileName.contains('/')) { "Path separators are rejected: $fileName" }
        val destination = incoming.resolve(fileName)
        require(!Files.exists(destination)) { "Destination already exists: $destination" }
        val stagingRoot = incoming.parent.resolve("staging")
        Files.createDirectories(stagingRoot)
        val staging = stagingRoot.resolve("$fileName.part")
        require(staging.fileSystem == destination.fileSystem) {
            "Atomic rename requires the same filesystem"
        }
        val sha256 = PayloadFiles.create(staging, sizeBytes)
        require(!Files.exists(destination)) { "Partial file must not appear in incoming during staging" }
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return PublishedInput(destination, sizeBytes, sha256)
    }

    fun awaitFailure(
        id: String,
        namespace: String,
        timeout: Duration,
    ): FailureObservation = probeClient.awaitFailure(id, namespace, timeout)

    fun awaitRelease(
        attemptId: Long,
        timeout: Duration,
    ): ReleaseObservation = probeClient.awaitRelease(attemptId, timeout)

    fun resumeRetries(attemptId: Long) {
        probeClient.resumeRetries(attemptId)
    }

    fun awaitApplicationConnectionBaseline(timeout: Duration = Duration.ofSeconds(30)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (database.applicationConnectionCount() == jdbcConnectionBaseline) {
                return
            }
            Thread.sleep(50)
        }
        error(
            "Application JDBC connections did not return to baseline; " +
                "baseline=$jdbcConnectionBaseline current=${database.applicationConnectionCount()}",
        )
    }

    override fun close() {
        val failures = mutableListOf<Throwable>()
        fun capture(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                failures.add(failure)
            }
        }

        capture { probeClient.signalShutdown() }
        capture { releaseSftpTransportGates() }
        capture {
            val stop = application.stop()
            requireGracefulStop(stop)
        }
        capture { assertResourceBaseline() }
        capture { sftp.close() }
        capture { database.close() }
        capture { deleteDirectoryTree(root) }
        capture { probeClient.close() }

        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }

    private fun assertResourceBaseline() {
        check(sftp.openHandleCount <= sftpBaselineHandles) {
            "SFTP open handles must return to baseline; baseline=$sftpBaselineHandles current=${sftp.openHandleCount}"
        }
    }

    private fun requireGracefulStop(stop: StopResult) {
        check(!stop.forced) {
            "Packaged application must stop gracefully; exitCode=${stop.exitCode}"
        }
    }

    private fun deleteDirectoryTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { path ->
            Files.deleteIfExists(path)
        }
    }

    internal fun releaseSftpTransportGates() {
        sftp.releaseWriteFailure()
    }

    internal fun activeSftpTransportGateWaiters(): Int = sftp.activeWriteFailureGateWaiters()

    companion object {
        const val READINESS_SENTINEL_NAME = "readiness-sentinel.bin"
        const val READINESS_SENTINEL_BYTES = 4_096L

        fun start(
            directory: Path,
            heapMiB: Int = 256,
            applicationJar: Path,
            applicationLog: Path,
        ): CompositionFixture {
            CompositionProbe.resetForTests()
            var database: H2AuditDatabase? = null
            var sftp: ControllableSftpServer? = null
            var packagedApplication: PackagedApplication? = null
            var fileRoot: Path? = null
            try {
                Files.createDirectories(directory)
                fileRoot = directory.resolve("files")
                val incoming = fileRoot.resolve("incoming")
                val archive = fileRoot.resolve("archive")
                Files.createDirectories(incoming)
                Files.createDirectories(archive)

                val transportRoot = directory.resolve("probe-transport")
                CompositionProbeTransport.ensureLayout(transportRoot)
                database = H2AuditDatabase.start(directory.resolve("h2"))
                sftp = ControllableSftpServer(directory.resolve("sftp"), requestedPort = 0)
                sftp.start()

                publishReadinessInput(incoming, archive, sftp, database)

                val resolvedFileRoot = fileRoot
                val processWorkingDirectory = directory.resolve("process")
                Files.createDirectories(processWorkingDirectory)
                packagedApplication =
                    try {
                        PackagedApplication.start(
                            ProcessSpec(
                                jar = applicationJar,
                                log = applicationLog,
                                workingDirectory = processWorkingDirectory,
                                jvmArgs =
                                    listOf(
                                        "-Xmx${heapMiB}m",
                                        "-D${CompositionSystemProperties.FILE_DIRECTORY}=${resolvedFileRoot.toAbsolutePath()}",
                                        "-D${CompositionSystemProperties.SFTP_HOST}=${EmbeddedSftpServer.HOST}",
                                        "-D${CompositionSystemProperties.SFTP_PORT}=${sftp.port}",
                                        "-D${CompositionSystemProperties.SFTP_USERNAME}=${EmbeddedSftpServer.USERNAME}",
                                        "-D${CompositionSystemProperties.SFTP_PASSWORD}=${EmbeddedSftpServer.PASSWORD}",
                                        "-D${CompositionSystemProperties.DB_JDBC_URL}=${database.jdbcUrl}",
                                        "-D${CompositionSystemProperties.DB_USER}=${database.user}",
                                        "-D${CompositionSystemProperties.DB_PASSWORD}=${database.password}",
                                        "-D${CompositionProbeTransport.TRANSPORT_DIR_PROPERTY}=${transportRoot.toAbsolutePath()}",
                                    ),
                                applicationArgs = listOf("--spring.main.banner-mode=off"),
                                startupTimeout = Duration.ofSeconds(30),
                                stopTimeout = Duration.ofSeconds(10),
                                pollInterval = Duration.ofMillis(100),
                            ),
                            readiness = { application ->
                                operationalReadiness(
                                    application = application,
                                    processRoot = processWorkingDirectory,
                                    fileRoot = resolvedFileRoot,
                                    sftp = sftp,
                                    database = database,
                                )
                            },
                        )
                    } catch (failure: IllegalStateException) {
                        dumpReadinessState(processWorkingDirectory, fileRoot, sftp, database)
                        throw failure
                    }

                purgeReadinessArtifacts(fileRoot, sftp, database)
                database.rejectInserts()
                val baselineHandles = sftp.openHandleCount
                val jdbcBaseline = database.applicationConnectionCount()
                return CompositionFixture(
                    root = directory,
                    applicationJar = applicationJar,
                    applicationLog = applicationLog,
                    application = packagedApplication,
                    sftp = sftp,
                    database = database,
                    incoming = incoming,
                    archive = archive,
                    probeClient = CompositionProbeClient(transportRoot),
                    sftpBaselineHandles = baselineHandles,
                    jdbcConnectionBaseline = jdbcBaseline,
                )
            } catch (failure: Throwable) {
                runCatching { packagedApplication?.stop() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { sftp?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { database?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }

        private fun publishReadinessInput(
            incoming: Path,
            archive: Path,
            sftp: ControllableSftpServer,
            database: H2AuditDatabase,
        ) {
            val stagingRoot = incoming.parent.resolve("staging")
            Files.createDirectories(stagingRoot)
            val staging = stagingRoot.resolve("$READINESS_SENTINEL_NAME.part")
            PayloadFiles.create(staging, READINESS_SENTINEL_BYTES)
            Files.move(
                staging,
                incoming.resolve(READINESS_SENTINEL_NAME),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        private fun operationalReadiness(
            application: PackagedApplication,
            processRoot: Path,
            fileRoot: Path,
            sftp: ControllableSftpServer,
            database: H2AuditDatabase,
        ): Boolean {
            if (!application.isAlive()) return false
            if (application.pid == ProcessHandle.current().pid()) return false
            val contextReady = Files.isRegularFile(processRoot.resolve(FixtureReadyMarker.CONTEXT_READY_FILE))
            val archived = fileRoot.resolve("archive/$READINESS_SENTINEL_NAME")
            val remote = sftp.home.resolve(READINESS_SENTINEL_NAME)
            val input = fileRoot.resolve("incoming/$READINESS_SENTINEL_NAME")
            val auditReady =
                database.rows().any { it.id == READINESS_SENTINEL_NAME && it.byteCount == READINESS_SENTINEL_BYTES }
            if (contextReady && Files.isRegularFile(archived) && !Files.exists(input) && Files.isRegularFile(remote) && auditReady) {
                return true
            }
            return false
        }

        private fun dumpReadinessState(
            processRoot: Path,
            fileRoot: Path,
            sftp: ControllableSftpServer,
            database: H2AuditDatabase,
        ) {
            System.err.println(
                buildString {
                    appendLine("Composition readiness diagnostics:")
                    appendLine("  contextReady=${Files.exists(processRoot.resolve(FixtureReadyMarker.CONTEXT_READY_FILE))}")
                    appendLine("  incomingSentinel=${Files.exists(fileRoot.resolve("incoming/$READINESS_SENTINEL_NAME"))}")
                    appendLine("  archivedSentinel=${Files.exists(fileRoot.resolve("archive/$READINESS_SENTINEL_NAME"))}")
                    appendLine("  remoteSentinel=${Files.exists(sftp.home.resolve(READINESS_SENTINEL_NAME))}")
                    appendLine("  auditRows=${database.rows()}")
                },
            )
        }

        private fun purgeReadinessArtifacts(
            fileRoot: Path,
            sftp: ControllableSftpServer,
            database: H2AuditDatabase,
        ) {
            Files.deleteIfExists(fileRoot.resolve("incoming/$READINESS_SENTINEL_NAME"))
            Files.deleteIfExists(fileRoot.resolve("archive/$READINESS_SENTINEL_NAME"))
            Files.deleteIfExists(sftp.home.resolve(READINESS_SENTINEL_NAME))
            database.removeRow(READINESS_SENTINEL_NAME)
            check(database.rows().isEmpty()) { "Audit table must be empty after readiness purge" }
            check(!Files.exists(fileRoot.resolve("incoming/$READINESS_SENTINEL_NAME")))
        }
    }
}

package dev.museframework.e2e.composition

import dev.museframework.e2e.testkit.ProcessFileProbe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackagedConnectorCompositionE2ETest {
    private val applicationJar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
    private val applicationLog = Path.of(requiredProperty("packaged.e2e.log")).toAbsolutePath()

    @EnabledOnOs(OS.LINUX, OS.MAC)
    @Test
    fun `DB audit failure preserves completed remote side effect and retries the source`() {
        Files.createTempDirectory("composition-db-failure-").let { directory ->
            CompositionFixture.start(directory, applicationJar = applicationJar, applicationLog = applicationLog).use { fixture ->
                // Fixture start leaves rejectInserts armed after readiness purge.
                assertTrue(fixture.database.rows().isEmpty())
                assertTrue(fixture.application.isAlive())

                val input = fixture.publishInput("db-failure.bin", 1_048_576L)
                val failure =
                    fixture.awaitFailure(
                        id = "db-failure.bin",
                        namespace = "DB",
                        timeout = Duration.ofSeconds(120),
                    )
                assertEquals("QUERY_EXECUTION", failure.identifier)

                val remote = fixture.sftp.home.resolve("db-failure.bin")
                assertTrue(Files.isRegularFile(remote))
                assertEquals(input.sizeBytes, Files.size(remote))
                assertEquals(input.sha256, PayloadFiles.sha256(remote))
                assertTrue(Files.exists(input.path))
                assertFalse(Files.exists(fixture.archive.resolve("db-failure.bin")))
                assertTrue(fixture.database.rows().none { it.id == "db-failure.bin" })
                assertTrue(fixture.application.isAlive())
                assertTrue(fixture.sftp.completedWriteCount("db-failure.bin") >= 1)

                val release = fixture.awaitRelease(failure.attemptId, Duration.ofSeconds(60))
                assertEquals(failure.attemptId, release.attemptId)
                assertTrue(release.providerClosed)
                assertFalse(release.managed)

                awaitClosedInputDescriptor(fixture, input.path, Duration.ofSeconds(60))
                awaitSftpHandleBaseline(fixture, Duration.ofSeconds(60))
                fixture.awaitApplicationConnectionBaseline(Duration.ofSeconds(30))
                assertTrue(Files.exists(input.path))
                assertFalse(Files.exists(fixture.archive.resolve("db-failure.bin")))
                assertTrue(fixture.database.rows().isEmpty())

                val writesAfterFailure = fixture.sftp.completedWriteCount("db-failure.bin")
                fixture.database.allowInserts()
                fixture.resumeRetries(failure.attemptId)
                awaitSuccessfulJourney(fixture, input, Duration.ofSeconds(120))
                assertEquals(input.sha256, PayloadFiles.sha256(fixture.archive.resolve("db-failure.bin")))
                assertEquals(input.sha256, PayloadFiles.sha256(fixture.sftp.home.resolve("db-failure.bin")))
                assertEquals(listOf(AuditRow("db-failure.bin", 1_048_576L)), fixture.database.rows())
                assertTrue(
                    fixture.sftp.completedWriteCount("db-failure.bin") >= writesAfterFailure + 1,
                    "Recovery must complete at least one additional remote write",
                )
                assertTrue(fixture.sftp.completedWriteCount("db-failure.bin") >= 2)

                val next = fixture.publishInput("next-after-db-failure.bin", 65_536L)
                awaitSuccessfulJourney(fixture, next, Duration.ofSeconds(120))
                assertEquals(
                    listOf(
                        AuditRow("db-failure.bin", 1_048_576L),
                        AuditRow("next-after-db-failure.bin", 65_536L),
                    ).sortedBy { it.id },
                    fixture.database.rows().sortedBy { it.id },
                )
            }
            assertFalse(Files.exists(directory))
        }
    }

    @Test
    fun `successful composition transfers and audits a polled file`() {
        Files.createTempDirectory("composition-success-").let { directory ->
            CompositionFixture.start(directory, applicationJar = applicationJar, applicationLog = applicationLog).use { fixture ->
                fixture.database.allowInserts()
                val input = fixture.publishInput("success.bin", 1_048_576L)
                awaitSuccessfulJourney(fixture, input, Duration.ofSeconds(120))

                assertEquals(1_048_576L, Files.size(fixture.archive.resolve("success.bin")))
                assertEquals(input.sha256, PayloadFiles.sha256(fixture.archive.resolve("success.bin")))
                assertEquals(input.sha256, PayloadFiles.sha256(fixture.sftp.home.resolve("success.bin")))
                assertEquals(listOf(AuditRow("success.bin", 1_048_576L)), fixture.database.rows())
                assertFalse(Files.exists(input.path))
            }
        }
    }

    @Test
    fun `composition streams a file larger than its child heap`() {
        Files.createTempDirectory("composition-large-").let { directory ->
            CompositionFixture.start(
                directory,
                heapMiB = 256,
                applicationJar = applicationJar,
                applicationLog = applicationLog,
            ).use { fixture ->
                fixture.database.allowInserts()
                val input = fixture.publishInput("large.bin", 536_870_912L)
                awaitSuccessfulJourney(fixture, input, Duration.ofSeconds(180))

                assertEquals(536_870_912L, Files.size(fixture.archive.resolve("large.bin")))
                assertEquals(input.sha256, PayloadFiles.sha256(fixture.archive.resolve("large.bin")))
                assertEquals(input.sha256, PayloadFiles.sha256(fixture.sftp.home.resolve("large.bin")))
                assertEquals(listOf(AuditRow("large.bin", 536_870_912L)), fixture.database.rows())
                assertFalse(Files.exists(input.path))
            }
        }
    }

    @Test
    fun `fixture close aggregates injected database stop failures after listener shutdown`() {
        Files.createTempDirectory("composition-fixture-h2-close-").let { directory ->
            val baseline = ExternalServerProbe.h2TcpServerThreadCount()
            val fixture =
                CompositionFixture.start(directory, applicationJar = applicationJar, applicationLog = applicationLog)
            fixture.database.closeFailureForTests = IllegalStateException("injected fixture H2 close failure")
            val failure =
                assertFailsWith<IllegalStateException> {
                    fixture.close()
                }
            assertEquals("injected fixture H2 close failure", failure.message)
            ExternalServerProbe.awaitH2TcpServerThreadCount(baseline, Duration.ofSeconds(10))
            assertFalse(Files.exists(directory))
        }
    }

    @Test
    fun `fixture close releases paused SFTP transport and stops gracefully`() {
        Files.createTempDirectory("composition-teardown-transport-").let { directory ->
            CompositionFixture.start(directory, applicationJar = applicationJar, applicationLog = applicationLog).use { fixture ->
                fixture.database.allowInserts()
                fixture.sftp.armWriteFailure(afterBytes = 32_768L)
                fixture.publishInput("paused.bin", 65_536L)
                fixture.sftp.awaitWriteFailureReady(Duration.ofSeconds(120))
                assertTrue(fixture.activeSftpTransportGateWaiters() >= 1)
            }
            assertFalse(Files.exists(directory))
        }
    }

    @EnabledOnOs(OS.LINUX, OS.MAC)
    @Test
    fun `mid-transfer SFTP failure preserves source and releases owned resources`() {
        Files.createTempDirectory("composition-mid-transfer-").let { directory ->
            CompositionFixture.start(directory, applicationJar = applicationJar, applicationLog = applicationLog).use { fixture ->
                fixture.database.allowInserts()
                fixture.sftp.armWriteFailure(afterBytes = 65_536L)
                val input = fixture.publishInput("failed.bin", 1_048_576L)
                val observation = fixture.sftp.awaitWriteFailureReady(Duration.ofSeconds(120))

                assertTrue(fixture.application.isAlive())
                assertEquals("failed.bin", observation.remotePath.removePrefix("/"))
                assertTrue(observation.acceptedBytes > 0L)
                assertTrue(observation.acceptedBytes < input.sizeBytes)
                val remote = fixture.sftp.home.resolve("failed.bin")
                assertTrue(Files.isRegularFile(remote))
                assertTrue(Files.size(remote) > 0L)
                assertTrue(Files.size(remote) < input.sizeBytes)
                assertTrue(Files.exists(input.path))
                assertFalse(Files.exists(fixture.archive.resolve("failed.bin")))
                assertTrue(fixture.database.rows().none { it.id == "failed.bin" })

                awaitOpenInputDescriptor(fixture, input.path, Duration.ofSeconds(30))

                fixture.sftp.releaseWriteFailure()
                val failure =
                    fixture.awaitFailure(
                        id = "failed.bin",
                        namespace = "SFTP",
                        timeout = Duration.ofSeconds(120),
                    )
                assertEquals("OPERATION_FAILED", failure.identifier)
                val release = fixture.awaitRelease(failure.attemptId, Duration.ofSeconds(60))
                assertEquals(failure.attemptId, release.attemptId)
                assertTrue(release.providerClosed)
                assertFalse(release.managed)

                awaitClosedInputDescriptor(fixture, input.path, Duration.ofSeconds(60))
                awaitSftpHandleBaseline(fixture, Duration.ofSeconds(60))
                assertTrue(Files.exists(input.path))
                assertFalse(Files.exists(fixture.archive.resolve("failed.bin")))
                assertTrue(fixture.database.rows().isEmpty())
                assertTrue(Files.isRegularFile(remote))
                assertTrue(Files.size(remote) < input.sizeBytes)
                assertTrue(PayloadFiles.sha256(remote) != input.sha256)

                fixture.sftp.clearWriteFailure()
                fixture.resumeRetries(failure.attemptId)
                awaitSuccessfulJourney(fixture, input, Duration.ofSeconds(120))
                assertEquals(input.sha256, PayloadFiles.sha256(fixture.archive.resolve("failed.bin")))
                assertEquals(input.sha256, PayloadFiles.sha256(fixture.sftp.home.resolve("failed.bin")))
                assertEquals(listOf(AuditRow("failed.bin", 1_048_576L)), fixture.database.rows())

                val second = fixture.publishInput("second.bin", 65_536L)
                awaitSuccessfulJourney(fixture, second, Duration.ofSeconds(120))
                assertEquals(
                    listOf(
                        AuditRow("failed.bin", 1_048_576L),
                        AuditRow("second.bin", 65_536L),
                    ).sortedBy { it.id },
                    fixture.database.rows().sortedBy { it.id },
                )
            }
            assertFalse(Files.exists(directory))
        }
    }

    @EnabledOnOs(OS.LINUX, OS.MAC)
    @Test
    fun `completed composition releases local descriptors and settles external resources`() {
        Files.createTempDirectory("composition-resources-").let { directory ->
            CompositionFixture.start(directory, applicationJar = applicationJar, applicationLog = applicationLog).use { fixture ->
                fixture.database.allowInserts()
                val input = fixture.publishInput("resources.bin", 65_536L)
                awaitSuccessfulJourney(fixture, input, Duration.ofSeconds(120))

                val openPaths = ProcessFileProbe.openPaths(fixture.application.pid)
                val fileRoot = fixture.incoming.parent.toRealPath()
                assertTrue(openPaths.none { it.startsWith(fileRoot) })

                assertEquals(fixture.sftpOpenHandleBaseline, fixture.sftp.openHandleCount)
                fixture.awaitApplicationConnectionBaseline(Duration.ofSeconds(30))
            }
        }
    }

    private fun awaitSuccessfulJourney(
        fixture: CompositionFixture,
        input: PublishedInput,
        timeout: Duration,
    ) {
        val fileName = input.path.fileName.toString()
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (!fixture.application.isAlive()) {
                error("Composition application exited before journey completed")
            }
            val archived = fixture.archive.resolve(fileName)
            val remote = fixture.sftp.home.resolve(fileName)
            val rows = fixture.database.rows()
            if (!Files.exists(input.path) &&
                Files.isRegularFile(archived) &&
                Files.isRegularFile(remote) &&
                rows.any { it.id == fileName && it.byteCount == input.sizeBytes }
            ) {
                return
            }
            Thread.sleep(100)
        }
        error("Timed out waiting for composition journey for $fileName")
    }

    private fun awaitOpenInputDescriptor(
        fixture: CompositionFixture,
        input: Path,
        timeout: Duration,
    ) {
        val realInput = input.toRealPath()
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            check(fixture.application.isAlive()) { "Child exited while waiting for open input descriptor" }
            if (ProcessFileProbe.openPaths(fixture.application.pid).any { it == realInput || it.endsWith(realInput.fileName) }) {
                return
            }
            Thread.sleep(50)
        }
        error("Input descriptor was never observed open for $realInput")
    }

    private fun awaitClosedInputDescriptor(
        fixture: CompositionFixture,
        input: Path,
        timeout: Duration,
    ) {
        val realInput = input.toRealPath()
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            check(fixture.application.isAlive()) { "Child exited while waiting for input descriptor release" }
            val open =
                ProcessFileProbe.openPaths(fixture.application.pid).any {
                    it == realInput || it.endsWith(realInput.fileName)
                }
            if (!open) return
            Thread.sleep(50)
        }
        error("Input descriptor remained open for $realInput")
    }

    private fun awaitSftpHandleBaseline(
        fixture: CompositionFixture,
        timeout: Duration,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (fixture.sftp.openHandleCount <= fixture.sftpOpenHandleBaseline) {
                return
            }
            Thread.sleep(50)
        }
        error(
            "SFTP handles did not return to baseline; " +
                "baseline=${fixture.sftpOpenHandleBaseline} current=${fixture.sftp.openHandleCount}",
        )
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Required system property is missing: $name" }
}

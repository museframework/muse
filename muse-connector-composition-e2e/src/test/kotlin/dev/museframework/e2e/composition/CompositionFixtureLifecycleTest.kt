package dev.museframework.e2e.composition

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CompositionFixtureLifecycleTest {
    @Test
    fun `H2 initialization failure returns TCP listener count to baseline`(
        @TempDir directory: Path,
    ) {
        val h2Directory = directory.resolve("h2")
        val baseline = ExternalServerProbe.h2TcpServerThreadCount()
        Files.createDirectories(h2Directory.resolve("composition-audit.mv.db"))
        assertFailsWith<Exception> {
            H2AuditDatabase.start(h2Directory)
        }
        ExternalServerProbe.awaitH2TcpServerThreadCount(baseline, Duration.ofSeconds(10))
        assertFalse(Files.exists(h2Directory.resolve("composition-audit.mv.db.lock")))
    }

    @Test
    fun `fixture startup failure during H2 initialization unwinds the fixture root`(
        @TempDir directory: Path,
    ) {
        val fixtureRoot = directory.resolve("fixture")
        val baseline = ExternalServerProbe.h2TcpServerThreadCount()
        Files.createDirectories(fixtureRoot.resolve("h2/composition-audit.mv.db"))
        val missingJar = directory.resolve("missing.jar")
        assertFailsWith<Exception> {
            CompositionFixture.start(
                fixtureRoot,
                applicationJar = missingJar,
                applicationLog = directory.resolve("startup.log"),
            )
        }
        ExternalServerProbe.awaitH2TcpServerThreadCount(baseline, Duration.ofSeconds(10))
        assertFalse(Files.exists(fixtureRoot.resolve("h2")))
        assertFalse(Files.exists(fixtureRoot.resolve("sftp")))
    }

    @Test
    fun `close propagates injected H2 stop failures after stopping the listener`(
        @TempDir directory: Path,
    ) {
        val baseline = ExternalServerProbe.h2TcpServerThreadCount()
        val database = H2AuditDatabase.start(directory.resolve("h2"))
        database.closeFailureForTests = IllegalStateException("injected H2 close failure")
        val failure =
            assertFailsWith<IllegalStateException> {
                database.close()
            }
        assertEquals("injected H2 close failure", failure.message)
        ExternalServerProbe.awaitH2TcpServerThreadCount(baseline, Duration.ofSeconds(10))
    }
}

package dev.museframework.e2e.file

import dev.museframework.e2e.testkit.PackagedApplication
import dev.museframework.e2e.testkit.ProcessFileProbe
import dev.museframework.e2e.testkit.ProcessSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledOnOs(OS.LINUX, OS.MAC)
class PackagedCsvStreamingE2ETest {
    @Test
    fun `generated CSV foreach processes a file larger than twice the child heap`() = withApplication { fixture ->
        val rows = 65_536
        fixture.writeCsv("large", rows, padding = 4096)
        assertTrue(Files.size(fixture.input("large")) > 2L * HEAP_BYTES)
        fixture.trigger("large")
        val report = fixture.awaitReport("large")
        assertEquals("SUCCESS", report[0])
        assertEquals(rows.toLong(), report[1].toLong())
        assertEquals(rows.toLong() * (rows + 1) / 2, report[2].toLong())
        fixture.assertReleased("large")
    }

    @Test
    fun `generated CSV foreach reads the tail only after the first body invocation`() = withApplication { fixture ->
        val rows = 10_000
        fixture.writeCsv("progressive", rows)
        fixture.trigger("progressive")
        fixture.await("first row marker") {
            val marker = fixture.root.resolve("csv/output/first-row")
            Files.exists(marker) && Files.readString(marker) == "1"
        }
        assertEquals("1", Files.readString(fixture.root.resolve("csv/output/first-row")))
        assertFalse(Files.exists(fixture.report("progressive")))
        assertTrue(fixture.input("progressive").toRealPath() in ProcessFileProbe.openPaths(fixture.application.pid))

        // Change unread input after the body has started. Eager parsing would have cached the valid tail.
        Files.newByteChannel(fixture.input("progressive"), StandardOpenOption.WRITE).use { channel ->
            channel.position(channel.size() - 71)
            channel.write(java.nio.ByteBuffer.wrap("oops!".toByteArray()))
        }
        Files.writeString(fixture.root.resolve("csv/release"), "continue")
        val report = fixture.awaitReport("progressive")
        assertEquals("FAILURE", report[0])
        assertEquals("MULE", report[3])
        assertEquals("EXPRESSION", report[4])
        assertEquals((rows - 1).toLong(), report[1].toLong())
        assertEquals((rows - 1L) * rows / 2, report[2].toLong())
        fixture.assertReleased("progressive")
    }

    @Test
    fun `invalid CSV record fails after completed rows and releases the file`() = withApplication { fixture ->
        val rows = 10_000
        fixture.writeCsv("malformed", rows, invalidRecordTail = true)
        fixture.trigger("malformed")
        val report = fixture.awaitReport("malformed")
        assertEquals("FAILURE", report[0])
        assertEquals("MULE", report[3])
        assertEquals("EXPRESSION", report[4])
        val completed = report[1].toLong()
        assertEquals(rows.toLong(), completed)
        assertEquals(completed * (completed + 1) / 2, report[2].toLong())
        fixture.assertReleased("malformed")
    }

    @Test
    fun `body failure stops CSV iteration and releases the remaining input`() = withApplication { fixture ->
        fixture.writeCsv("body-failure", 10_000)
        fixture.trigger("body-failure")
        val report = fixture.awaitReport("body-failure")
        assertEquals(listOf("FAILURE", "100", "5050", "APP", "BODY_FAILURE"), report)
        fixture.assertReleased("body-failure")
    }

    private fun withApplication(test: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("muse-csv-packaged-").toRealPath()
        val log = root.resolve("application.log")
        val scenarios = listOf("large", "progressive", "malformed", "body-failure")
        Files.createDirectories(root.resolve("incoming"))
        Files.createDirectories(root.resolve("generated/incoming"))
        scenarios.forEach { Files.createDirectories(root.resolve("csv/commands/$it")) }
        Files.createDirectories(root.resolve("csv/input"))
        Files.createDirectories(root.resolve("csv/output"))
        Files.createDirectories(root.resolve("tmp"))
        try {
            val application = PackagedApplication.start(
                ProcessSpec(
                    jar = Path.of(System.getProperty("packaged.csv.jar") ?: requireNotNull(System.getProperty("packaged.e2e.jar"))).toAbsolutePath(),
                    log = log,
                    workingDirectory = root,
                    jvmArgs = listOf("-Xmx128m", "-XX:+ExitOnOutOfMemoryError", "-Dpackaged.file.directory=$root", "-Djava.io.tmpdir=${root.resolve("tmp")}"),
                    startupTimeout = Duration.ofSeconds(45),
                    stopTimeout = Duration.ofSeconds(15),
                ),
                readiness = { Files.exists(root.resolve(FixtureReadyMarker.CONTEXT_READY_FILE)) },
            )
            var primaryFailure: Throwable? = null
            try {
                assertTrue(application.pid != ProcessHandle.current().pid())
                test(Fixture(root, application))
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                if (application.stop().forced) {
                    val cleanup = AssertionError("Child required forced termination; log: ${Files.readString(log).takeLast(6000)}")
                    if (primaryFailure == null) throw cleanup
                    primaryFailure.addSuppressed(cleanup)
                }
            }
        } finally {
            if (Files.exists(log)) {
                val retainedLog = Path.of(requireNotNull(System.getProperty("packaged.e2e.log")))
                    .toAbsolutePath().parent.resolve("${root.fileName}.log")
                Files.createDirectories(retainedLog.parent)
                Files.copy(log, retainedLog, StandardCopyOption.REPLACE_EXISTING)
            }
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(val root: Path, val application: PackagedApplication) {
        fun input(scenario: String): Path = root.resolve("csv/input/$scenario.csv")
        fun report(scenario: String): Path = root.resolve("csv/output/$scenario.report")

        fun writeCsv(scenario: String, rows: Int, padding: Int = 64, invalidRecordTail: Boolean = false) {
            Files.newBufferedWriter(input(scenario)).use { writer ->
                writer.appendLine("id,value")
                val value = "x".repeat(padding)
                for (id in 1..rows) writer.appendLine("$id,$value")
                if (invalidRecordTail) writer.appendLine("not-a-number,invalid-record")
            }
        }

        fun trigger(scenario: String) {
            val staging = root.resolve("$scenario.command")
            Files.writeString(staging, "start")
            Files.move(staging, root.resolve("csv/commands/$scenario/start"), StandardCopyOption.ATOMIC_MOVE)
        }

        fun awaitReport(scenario: String): List<String> {
            // Command deletion happens after the terminal result and source resource release.
            await("$scenario report and command completion") {
                Files.exists(report(scenario)) && !Files.exists(root.resolve("csv/commands/$scenario/start"))
            }
            return Files.readString(report(scenario)).trim().split('|')
        }

        fun assertReleased(scenario: String) {
            await("$scenario file descriptor release") {
                input(scenario).toAbsolutePath().normalize() !in ProcessFileProbe.openPaths(application.pid)
            }
            assertTrue(application.isAlive(), "Cleanup must be observed before the process exits")
        }

        fun await(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos()
            while (System.nanoTime() < deadline) {
                check(application.isAlive()) { "Child exited waiting for $description: ${logTail()}" }
                if (condition()) return
                Thread.sleep(50)
            }
            error("Timed out waiting for $description: ${logTail()}")
        }

        private fun logTail(): String = Files.readString(application.logPath).takeLast(6000)
    }

    private companion object {
        const val HEAP_BYTES = 128L * 1024 * 1024
    }
}

package dev.museframework.e2e.testkit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@EnabledOnOs(OS.LINUX, OS.MAC)
class ProcessFileProbeTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `observes a file with spaces closing while the child stays alive`() {
        val childJar = compileFakeChildJar(tempDirectory)
        val fixtureDirectory = Files.createDirectories(tempDirectory.resolve("fixture with spaces"))
        val heldFile = Files.writeString(fixtureDirectory.resolve("held file.txt"), "content")
        val observedPath = heldFile.toRealPath()
        val ready = fixtureDirectory.resolve("ready")
        val closeCommand = fixtureDirectory.resolve("close")
        val spec =
            ProcessSpec(
                jar = childJar,
                log = tempDirectory.resolve("descriptor-child.log"),
                workingDirectory = tempDirectory,
                applicationArgs = listOf("descriptor", ready.toString(), heldFile.toString(), closeCommand.toString()),
                startupTimeout = Duration.ofSeconds(3),
                stopTimeout = Duration.ofSeconds(2),
                pollInterval = Duration.ofMillis(10),
            )

        PackagedApplication.start(spec) { Files.exists(ready) }.use { application ->
            assertTrue(await(Duration.ofSeconds(3)) { observedPath in ProcessFileProbe.openPaths(application.pid) })

            Files.writeString(closeCommand, "close")

            assertTrue(await(Duration.ofSeconds(3)) { observedPath !in ProcessFileProbe.openPaths(application.pid) })
            assertTrue(application.isAlive())
        }
    }

    @Test
    fun `linux descriptor disappearance is the only ignored readlink race`() {
        val fdDirectory = Files.createDirectories(tempDirectory.resolve("fd"))
        val descriptor = fdDirectory.resolve("7")
        Files.createSymbolicLink(descriptor, tempDirectory.resolve("held"))

        val paths =
            ProcessFileProbe.readLinuxPaths(fdDirectory) {
                Files.delete(it)
                Files.readSymbolicLink(it)
            }

        assertTrue(paths.isEmpty())
    }

    @Test
    fun `inspection subprocess timeout terminates the observer process`() {
        val pidFile = tempDirectory.resolve("observer.pid")

        val failure =
            assertThrows(IllegalStateException::class.java) {
                ProcessFileProbe.runInspection(
                    listOf("/bin/sh", "-c", "echo \$\$ > \"\$1\"; exec sleep 5", "probe", pidFile.toString()),
                    Duration.ofMillis(100),
                )
            }

        assertTrue(failure.message.orEmpty().contains("timed out"))
        val pid = Files.readString(pidFile).trim().toLong()
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
    }

    @Test
    fun `mac output keeps spaces in whole n fields and rejects malformed fields`() {
        val path = tempDirectory.resolve("directory with spaces/file.txt").toAbsolutePath().normalize()

        assertTrue(path in ProcessFileProbe.parseLsof(listOf("p123", "f7", "n$path")))
        assertThrows(IllegalStateException::class.java) {
            ProcessFileProbe.parseLsof(listOf("p123", "f7", "n"))
        }
    }

    @Test
    fun `missing inspection tooling fails explicitly`() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                ProcessFileProbe.runInspection(
                    listOf(tempDirectory.resolve("missing-lsof").toString()),
                    Duration.ofSeconds(1),
                )
            }

        assertTrue(failure.message.orEmpty().contains("Could not start"))
    }

    @Test
    fun `large observer stdout does not deadlock waitFor`() {
        // ~200 KiB exceeds typical pipe buffer; redirect-to-file must still complete.
        val lines =
            ProcessFileProbe.runInspection(
                listOf(
                    "/bin/sh",
                    "-c",
                    "dd if=/dev/zero bs=1024 count=200 2>/dev/null | tr '\\0' 'a'; printf '\\n'",
                ),
                Duration.ofSeconds(5),
            )
        assertTrue(lines.joinToString("").length >= 200_000)
    }

    private fun await(
        timeout: Duration,
        observation: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (observation()) return true
            Thread.sleep(20)
        }
        return false
    }
}

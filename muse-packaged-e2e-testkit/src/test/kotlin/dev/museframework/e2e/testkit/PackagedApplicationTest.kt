package dev.museframework.e2e.testkit

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

class PackagedApplicationTest {
    @TempDir
    lateinit var tempDirectory: Path

    private lateinit var childJar: Path
    private val applications = CopyOnWriteArrayList<PackagedApplication>()

    @BeforeEach
    fun compileStandaloneChild() {
        childJar = compileFakeChildJar(tempDirectory)
    }

    @AfterEach
    fun stopChildren() {
        applications.forEach(PackagedApplication::close)
        applications.clear()
    }

    @Test
    fun `starts a standalone child and delegates readiness to the caller`() {
        val marker = tempDirectory.resolve("ready marker")
        val application =
            start(
                applicationArgs = listOf("ready", marker.toString()),
                jvmArgs = listOf("-Dfixture.jvm=present"),
            ) { Files.exists(marker) }

        assertNotEquals(ProcessHandle.current().pid(), application.pid)
        assertTrue(application.isAlive())
        assertEquals(tempDirectory.resolve("application.log").toAbsolutePath().normalize(), application.logPath)
        assertTrue(Files.readString(application.logPath).contains("jvm=present; mode=ready"))
    }

    @Test
    fun `reports an early exit with its code and preserved diagnostic log`() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                start(applicationArgs = listOf("exit")) { false }
            }

        assertTrue(failure.message.orEmpty().contains("17"))
        val log = tempDirectory.resolve("application.log")
        assertTrue(Files.exists(log))
        assertTrue(Files.readString(log).contains("synthetic child diagnostic"))
    }

    @Test
    fun `readiness timeout stops the child and preserves its log`() {
        val marker = tempDirectory.resolve("timeout-ready")
        val childPid = AtomicLong()

        val failure =
            assertThrows(IllegalStateException::class.java) {
                start(
                    applicationArgs = listOf("ready", marker.toString()),
                    startupTimeout = Duration.ofMillis(150),
                ) {
                    childPid.compareAndSet(0, it.pid)
                    false
                }
            }

        assertTrue(failure.message.orEmpty().contains("not ready"))
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false))
        assertTrue(Files.readString(tempDirectory.resolve("application.log")).contains("mode=ready"))
    }

    @Test
    fun `readiness failure stops the child and rethrows the same failure`() {
        val marker = tempDirectory.resolve("throw-ready")
        val childPid = AtomicLong()
        val expected = IllegalArgumentException("broken readiness")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                start(applicationArgs = listOf("ready", marker.toString())) {
                    childPid.set(it.pid)
                    throw expected
                }
            }

        assertSame(expected, failure)
        assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false))
    }

    @Test
    fun `graceful stop is idempotent and close may be repeated`() {
        val marker = tempDirectory.resolve("graceful-ready")
        val application = start(applicationArgs = listOf("ready", marker.toString())) { Files.exists(marker) }

        assertNotEquals(ProcessHandle.current().pid(), application.pid)
        val stopped = application.stop()
        assertFalse(stopped.forced)
        assertFalse(application.isAlive())
        assertEquals(stopped, application.stop())
        application.close()
        application.close()
    }

    @Test
    fun `rejects a missing child jar without starting a process`() {
        val missing = tempDirectory.resolve("missing.jar")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PackagedApplication.start(spec(jar = missing)) { true }
            }

        assertTrue(failure.message.orEmpty().contains(missing.toString()))
    }

    @Test
    fun `forces a stubborn child after the graceful deadline`() {
        val marker = tempDirectory.resolve("stubborn-ready")
        val application =
            start(
                applicationArgs = listOf("stubborn", marker.toString()),
                stopTimeout = Duration.ofMillis(100),
            ) { Files.exists(marker) }
        val pid = application.pid

        val stopped = application.stop()

        assertTrue(stopped.forced)
        assertFalse(application.isAlive())
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
        assertEquals(stopped, application.stop())
    }

    private fun start(
        applicationArgs: List<String>,
        jvmArgs: List<String> = emptyList(),
        startupTimeout: Duration = Duration.ofSeconds(3),
        stopTimeout: Duration = Duration.ofSeconds(2),
        readiness: (PackagedApplication) -> Boolean,
    ): PackagedApplication =
        PackagedApplication
            .start(
                spec(
                    jvmArgs = jvmArgs,
                    applicationArgs = applicationArgs,
                    startupTimeout = startupTimeout,
                    stopTimeout = stopTimeout,
                ),
                readiness,
            ).also(applications::add)

    private fun spec(
        jar: Path = childJar,
        jvmArgs: List<String> = emptyList(),
        applicationArgs: List<String> = emptyList(),
        startupTimeout: Duration = Duration.ofSeconds(3),
        stopTimeout: Duration = Duration.ofSeconds(2),
    ): ProcessSpec =
        ProcessSpec(
            jar = jar,
            log = tempDirectory.resolve("application.log"),
            workingDirectory = tempDirectory,
            jvmArgs = jvmArgs,
            applicationArgs = applicationArgs,
            startupTimeout = startupTimeout,
            stopTimeout = stopTimeout,
            pollInterval = Duration.ofMillis(10),
        )
}

internal fun compileFakeChildJar(directory: Path): Path {
    val source =
        Path.of(
            checkNotNull(PackagedApplicationTest::class.java.getResource("/fixtures/FakeChildApplication.java")).toURI(),
        )
    val classes = Files.createDirectories(directory.resolve("child-classes"))
    val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Tests require a JDK" }
    check(compiler.run(null, null, null, "-d", classes.toString(), source.toString()) == 0) {
        "Could not compile standalone child fixture"
    }

    val childJar = directory.resolve("fake-child.jar")
    val manifest =
        Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "fixtures.FakeChildApplication"
        }
    JarOutputStream(Files.newOutputStream(childJar), manifest).use { jar ->
        jar.putNextEntry(JarEntry("fixtures/FakeChildApplication.class"))
        Files.copy(classes.resolve("fixtures/FakeChildApplication.class"), jar)
        jar.closeEntry()
    }
    return childJar
}

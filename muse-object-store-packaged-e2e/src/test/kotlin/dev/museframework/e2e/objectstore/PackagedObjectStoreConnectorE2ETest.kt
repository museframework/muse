package dev.museframework.e2e.objectstore

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagedObjectStoreConnectorE2ETest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `generated seven operation flow preserves payload and projects targets in a separate JVM`() {
        val result = runApplication("roundtrip", "roundtrip")
        assertEquals("incoming", result.getProperty("payload"))
        assertEquals("true", result.getProperty("present"))
        assertEquals("incoming", result.getProperty("saved"))
        assertEquals("{shared=named-value}", result.getProperty("all"))
        assertEquals("[shared]", result.getProperty("keys"))
        assertEquals("false", result.getProperty("removed"))
        assertEquals("fallback", result.getProperty("fallback"))
        assertEquals("{}", result.getProperty("cleared"))
        assertEquals("none", result.getProperty("error"))
    }

    @Test
    fun `default and named values survive process restart while transient and other namespaces stay empty`() {
        assertEquals("none", runApplication("write", "tenant-a").getProperty("error"))
        val restarted = runApplication("read", "tenant-a")
        assertEquals("incoming", restarted.getProperty("defaultValue"))
        assertEquals("named-value", restarted.getProperty("namedValue"))
        assertEquals("missing", restarted.getProperty("transientValue"))
        val isolated = runApplication("read", "tenant-b")
        assertEquals("missing", isolated.getProperty("defaultValue"))
        assertEquals("missing", isolated.getProperty("namedValue"))
        assertEquals("missing", isolated.getProperty("transientValue"))
    }

    @Test
    fun `packaged duplicate write reports the Object Store error type and preserves the previous value`() {
        runApplication("write", "duplicate")
        assertEquals("OS:KEY_ALREADY_EXISTS", runApplication("duplicate", "duplicate").getProperty("error"))
        assertEquals("incoming", runApplication("read", "duplicate").getProperty("defaultValue"))
    }

    @Test
    fun `CDATA expressions retain structured values and a null default remains missing`() {
        val structured = runApplication("cdata-structured", "cdata-structured")
        assertEquals("none", structured.getProperty("error"))
        assertEquals("true", structured.getProperty("storedIsMap"))
        assertEquals("{id=7}", structured.getProperty("stored"))

        val absent = runApplication("cdata-null", "cdata-null")
        assertEquals("OS:KEY_NOT_FOUND", absent.getProperty("error"))
    }

    private fun runApplication(scenario: String, namespace: String): Properties {
        val jar = Path.of(requireNotNull(System.getProperty("packaged.e2e.jar"))).toAbsolutePath()
        val output = directory.resolve("$scenario-$namespace.properties")
        val log = Path.of(requireNotNull(System.getProperty("packaged.e2e.log")))
            .toAbsolutePath().resolveSibling("$scenario-$namespace.log")
        Files.createDirectories(log.parent)
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", jar.toString(), scenario, namespace,
            directory.resolve("data").toString(), output.toString(),
        ).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.pid() != ProcessHandle.current().pid(), "Acceptance must run in another JVM")
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Child timed out: ${Files.readString(log)}")
            assertEquals(0, process.exitValue(), "Child failed: ${Files.readString(log)}")
            assertTrue(Files.isRegularFile(output), "Child must acknowledge completed operations: ${Files.readString(log)}")
            return Properties().apply { Files.newInputStream(output).use(::load) }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
        }
    }
}

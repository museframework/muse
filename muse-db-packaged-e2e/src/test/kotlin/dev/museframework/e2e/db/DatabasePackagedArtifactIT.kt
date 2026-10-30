package dev.museframework.e2e.db

import dev.museframework.e2e.testkit.PackagedApplication
import dev.museframework.e2e.testkit.ProcessSpec
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DatabasePackagedArtifactIT {
    @Test
    fun `packaged JAR includes database and HTTP runtimes and excludes foreign connectors and test helpers`() {
        val jar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
        assertTrue(Files.isRegularFile(jar), "Packaged JAR must exist: $jar")

        JarFile(jar.toFile()).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toSet()
            val libraries =
                entries
                    .filter { it.startsWith("BOOT-INF/lib/") }
                    .map { it.removePrefix("BOOT-INF/lib/") }
                    .toList()

            assertTrue(
                libraries.any { library ->
                    library.startsWith("muse-db-connector-") &&
                        !library.startsWith("muse-db-connector-api-")
                },
                "JAR must include muse-db-connector runtime (not only API)",
            )
            assertTrue(
                libraries.any { it.startsWith("h2-") },
                "JAR must bundle the H2 JDBC driver",
            )
            assertTrue(
                libraries.any { it.startsWith("muse-http-connector-spring-") },
                "JAR must include muse-http-connector-spring adapter",
            )

            val forbiddenPrefixes =
                listOf(
                    "muse-sftp-connector-",
                    "muse-file-connector-",
                    "muse-policy-extension-fixture-",
                    "muse-packaged-e2e-testkit-",
                    "muse-sftp-connector-testkit-",
                    "junit-jupiter-",
                    "junit-platform-",
                )
            forbiddenPrefixes.forEach { prefix ->
                assertTrue(
                    libraries.none { it.startsWith(prefix) },
                    "JAR must not include forbidden library prefix '$prefix'; found=${libraries.filter { it.startsWith(prefix) }}",
                )
            }
        }
    }

    @Test
    fun `packaged database flow fails when H2 is omitted from the application JAR`() {
        val sourceJar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
        val scratchDirectory = Files.createTempDirectory("muse-db-packaged-artifact-scratch-")
        val jarWithoutH2 = scratchDirectory.resolve("application-without-h2.jar")
        val applicationLog = scratchDirectory.resolve("without-h2.log")
        val port = reserveExclusiveHttpPort()

        try {
            assertPortAvailable(port)
            copyJarExcludingH2(sourceJar, jarWithoutH2)
            JarFile(jarWithoutH2.toFile()).use { archive ->
                val libraries =
                    archive.entries()
                        .asSequence()
                        .map { it.name }
                        .filter { it.startsWith("BOOT-INF/lib/h2-") }
                        .toList()
                assertTrue(libraries.isEmpty(), "Scratch JAR must omit H2 libraries; found=$libraries")
            }

            val client =
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()

            PackagedApplication.start(
                ProcessSpec(
                    jar = jarWithoutH2,
                    log = applicationLog,
                    workingDirectory = scratchDirectory,
                    applicationArgs =
                        listOf(
                            "--server.port=$port",
                            "--spring.main.banner-mode=off",
                        ),
                    startupTimeout = Duration.ofSeconds(30),
                    stopTimeout = Duration.ofSeconds(10),
                    pollInterval = Duration.ofMillis(100),
                ),
                readiness = { application -> childEchoReady(application, port, client) },
            ).use { application ->
                val databaseRequest =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/e2e/database"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "text/plain; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString("ignored", StandardCharsets.UTF_8))
                        .build()
                val databaseResponse =
                    client.send(databaseRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

                assertChildJdbcDriverLoadFailure(applicationLog, databaseResponse)

                val stopResult = application.stop()
                assertFalse(stopResult.forced, "Scratch application stop must be graceful")
            }
        } finally {
            scratchDirectory.toFile().deleteRecursively()
        }
    }

    private fun copyJarExcludingH2(
        source: Path,
        destination: Path,
    ) {
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        val rewritten = destination.resolveSibling("${destination.fileName}.rewritten")
        JarFile(destination.toFile()).use { input ->
            JarOutputStream(Files.newOutputStream(rewritten)).use { output ->
                input.entries().asSequence().forEach { entry ->
                    if (entry.name.startsWith("BOOT-INF/lib/h2-")) {
                        return@forEach
                    }
                    val newEntry = JarEntry(entry.name)
                    newEntry.time = entry.time
                    if (entry.method == ZipEntry.STORED) {
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = entry.size
                        newEntry.crc = entry.crc
                    }
                    output.putNextEntry(newEntry)
                    input.getInputStream(entry).transferTo(output)
                    output.closeEntry()
                }
            }
        }
        Files.move(rewritten, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun childEchoReady(
        application: PackagedApplication,
        port: Int,
        client: HttpClient,
    ): Boolean {
        if (!application.isAlive()) return false
        if (application.pid == ProcessHandle.current().pid()) return false
        return try {
            val request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/e2e/echo"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString("ready", StandardCharsets.UTF_8))
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            response.statusCode() == 200 && response.body() == "Echo: ready"
        } catch (_: Exception) {
            false
        }
    }

    private fun assertChildJdbcDriverLoadFailure(
        applicationLog: Path,
        databaseResponse: HttpResponse<String>,
    ) {
        assertNotEquals(
            404,
            databaseResponse.statusCode(),
            "404 indicates a foreign HTTP listener without the packaged database route",
        )
        assertTrue(
            databaseResponse.statusCode() >= 500,
            "Missing H2 must surface as a server error from the child application; " +
                "status=${databaseResponse.statusCode()} body=${databaseResponse.body()}",
        )
        assertTrue(
            databaseResponse.body().contains("Cannot load JDBC driver") &&
                databaseResponse.body().contains("org.h2.Driver"),
            "Response body must report child JDBC driver load failure; body=${databaseResponse.body()}",
        )
        assertFalse(
            databaseResponse.body() == "Packaged database",
            "Database flow must not succeed without bundled H2",
        )

        assertTrue(Files.isRegularFile(applicationLog), "Application log must exist: $applicationLog")
        val logText = Files.readString(applicationLog)
        assertTrue(
            logText.contains("ClassNotFoundException") && logText.contains("org.h2.Driver"),
            "Application log must contain ClassNotFoundException for org.h2.Driver from the child JVM",
        )
    }

    private fun reserveExclusiveHttpPort(): Int =
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            socket.localPort
        }

    private fun assertPortAvailable(port: Int) {
        try {
            ServerSocket(port).use { socket -> socket.reuseAddress = true }
        } catch (failure: Exception) {
            throw IllegalStateException("Packaged E2E port $port is unavailable", failure)
        }
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Required system property is missing: $name" }
}

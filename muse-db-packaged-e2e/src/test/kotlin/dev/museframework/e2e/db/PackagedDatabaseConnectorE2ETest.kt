package dev.museframework.e2e.db

import dev.museframework.e2e.testkit.PackagedApplication
import dev.museframework.e2e.testkit.ProcessSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackagedDatabaseConnectorE2ETest {
    private val temporaryDirectory: Path = Files.createTempDirectory("muse-db-packaged-e2e-")

    private val port = requiredProperty("packaged.e2e.port").toInt()
    private val applicationJar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
    private val baseApplicationLog = Path.of(requiredProperty("packaged.e2e.log")).toAbsolutePath()
    private val applicationLog: Path
        get() = baseApplicationLog.resolveSibling("database-connector.log")
    private val client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()

    private lateinit var packagedApplication: PackagedApplication

    @BeforeAll
    fun startPackagedApplication() {
        assertPortAvailable(port)

        packagedApplication =
            PackagedApplication.start(
                ProcessSpec(
                    jar = applicationJar,
                    log = applicationLog,
                    workingDirectory = temporaryDirectory,
                    applicationArgs =
                        listOf(
                            "--server.port=$port",
                            "--spring.main.banner-mode=off",
                        ),
                    startupTimeout = STARTUP_TIMEOUT,
                    stopTimeout = PROCESS_STOP_TIMEOUT,
                    pollInterval = READINESS_POLL_INTERVAL,
                ),
                readiness = { probeEchoReady() },
            )

        check(packagedApplication.pid != ProcessHandle.current().pid()) {
            "Packaged application must run in a separate JVM"
        }
    }

    @AfterAll
    fun stopPackagedApplication() {
        try {
            if (::packagedApplication.isInitialized) {
                val stopResult = packagedApplication.stop()
                assertFalse(stopResult.forced, "Packaged database application stop must be graceful")
            }
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `packaged database flow executes against bundled JDBC driver`() {
        val response = post("/e2e/database", "ignored")

        assertEquals(200, response.statusCode())
        assertEquals("Packaged database", response.body())
    }

    private fun probeEchoReady(): Boolean =
        try {
            val response = post("/e2e/echo", "ready", REQUEST_TIMEOUT)
            response.statusCode() == 200 && response.body() == "Echo: ready"
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            false
        }

    private fun post(
        path: String,
        body: String,
        timeout: Duration = Duration.ofSeconds(5),
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port$path"))
                .timeout(timeout)
                .header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()

        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
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

    private companion object {
        val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(30)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(2)
        val PROCESS_STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READINESS_POLL_INTERVAL: Duration = Duration.ofMillis(100)
    }
}

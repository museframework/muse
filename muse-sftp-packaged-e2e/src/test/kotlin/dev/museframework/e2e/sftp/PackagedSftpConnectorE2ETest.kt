package dev.museframework.e2e.sftp

import dev.museframework.e2e.testkit.PackagedApplication
import dev.museframework.e2e.testkit.ProcessSpec
import dev.museframework.sftp.testkit.EmbeddedSftpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PackagedSftpConnectorE2ETest {
    private val temporaryDirectory: Path = Files.createTempDirectory("muse-sftp-packaged-e2e-")

    private val port = requiredProperty("packaged.e2e.port").toInt()
    private val applicationJar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
    private val baseApplicationLog = Path.of(requiredProperty("packaged.e2e.log")).toAbsolutePath()
    private val applicationLog: Path
        get() = baseApplicationLog.resolveSibling("sftp-connector.log")
    private val client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()

    private lateinit var sftpServer: EmbeddedSftpServer
    private lateinit var packagedApplication: PackagedApplication

    @BeforeAll
    fun startPackagedApplication() {
        assertPortAvailable(port)
        sftpServer = EmbeddedSftpServer(temporaryDirectory.resolve("sftp"), requestedPort = GENERATED_SFTP_PORT)
        sftpServer.start()
        assertEquals(GENERATED_SFTP_PORT, sftpServer.port)

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
                            "--packaged.sftp.host=${EmbeddedSftpServer.HOST}",
                            "--packaged.sftp.port=$GENERATED_SFTP_PORT",
                            "--packaged.sftp.username=${EmbeddedSftpServer.USERNAME}",
                            "--packaged.sftp.password=${EmbeddedSftpServer.PASSWORD}",
                        ),
                    startupTimeout = STARTUP_TIMEOUT,
                    stopTimeout = PROCESS_STOP_TIMEOUT,
                    pollInterval = READINESS_POLL_INTERVAL,
                ),
                readiness = { probeEchoReady() },
            )
    }

    @AfterAll
    fun stopPackagedApplication() {
        try {
            if (::packagedApplication.isInitialized) {
                val stopResult = packagedApplication.stop()
                assertFalse(stopResult.forced, "Packaged SFTP application stop must be graceful")
                awaitSessionDrain()
                assertEquals(0, sftpServer.activeSessionCount)
            }
        } finally {
            if (::sftpServer.isInitialized) sftpServer.close()
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    @Order(1)
    @Test
    fun `packaged application streams request through a real SFTP server`() {
        val payload = ByteArray(512 * 1024) { index -> ('a'.code + index % 26).toByte() }

        val response = post("/e2e/sftp", payload, Duration.ofSeconds(20))

        assertEquals(200, response.statusCode())
        assertContentEquals(payload, response.body())
        assertContentEquals(payload, Files.readAllBytes(sftpServer.home.resolve("outbound/request.bin")))
    }

    @Order(2)
    @Test
    fun `generated packaged application streams request through literal SFTP port 18822`() {
        val payload = ByteArray(512 * 1024) { index -> ('z'.code - index % 26).toByte() }

        val response = post("/e2e/sftp-generated", payload, Duration.ofSeconds(20))

        assertEquals(200, response.statusCode())
        assertContentEquals(payload, response.body())
        assertContentEquals(
            payload,
            Files.readAllBytes(sftpServer.home.resolve("outbound/generated-request.bin")),
        )
        assertEquals(GENERATED_SFTP_PORT, sftpServer.port)
    }

    private fun probeEchoReady(): Boolean =
        try {
            val response = post("/e2e/echo", "ready".toByteArray(), REQUEST_TIMEOUT)
            response.statusCode() == 200 && response.body().decodeToString() == "Echo: ready"
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            false
        }

    private fun post(
        path: String,
        body: ByteArray,
        timeout: Duration,
    ): HttpResponse<ByteArray> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(timeout)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun assertPortAvailable(port: Int) {
        try {
            ServerSocket(port).use { socket -> socket.reuseAddress = true }
        } catch (failure: Exception) {
            throw IllegalStateException("Packaged E2E port $port is unavailable", failure)
        }
    }

    private fun awaitSessionDrain() {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (sftpServer.activeSessionCount == 0) return
            Thread.sleep(50)
        }
        error("SFTP sessions did not drain after application stop; count=${sftpServer.activeSessionCount}")
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Required system property is missing: $name" }

    private companion object {
        const val GENERATED_SFTP_PORT = 18822
        val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(30)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(2)
        val PROCESS_STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READINESS_POLL_INTERVAL: Duration = Duration.ofMillis(100)
    }
}

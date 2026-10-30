package dev.museframework.e2e.packaged

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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.name
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Packaged JAR process fixture: one child JVM per test class, real HTTP through
 * `java.net.http.HttpClient`, and a per-instance log and spool directory. It declares no tests of its
 * own, so every subclass runs only its own suite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PackagedApplicationProcessSupport {
    /** Fixture identity: names this instance's application log and spool directory. */
    protected abstract val instanceId: String

    /** Extra application arguments, typically the streaming strategy of this instance. */
    protected abstract fun applicationArguments(tempDirectory: Path): List<String>

    protected val port = requiredProperty("packaged.e2e.port").toInt()
    private val applicationJar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
    private val baseApplicationLog = Path.of(requiredProperty("packaged.e2e.log")).toAbsolutePath()
    private val applicationLog: Path
        get() = baseApplicationLog.resolveSibling("streaming-$instanceId.log")
    private val fixtureTempDirectory: Path
        get() = baseApplicationLog.parent.resolve("streaming-$instanceId")
    private val client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()

    private lateinit var packagedApplication: PackagedApplication

    @BeforeAll
    fun startPackagedApplication() {
        assertPortAvailable()
        Files.createDirectories(fixtureTempDirectory)
        streamingTempFiles().forEach(Files::deleteIfExists)

        packagedApplication =
            PackagedApplication.start(
                ProcessSpec(
                    jar = applicationJar,
                    log = applicationLog,
                    workingDirectory = fixtureTempDirectory,
                    applicationArgs =
                        listOf(
                            "--server.port=$port",
                            "--spring.main.banner-mode=off",
                        ) + applicationArguments(fixtureTempDirectory),
                    startupTimeout = STARTUP_TIMEOUT,
                    stopTimeout = PROCESS_STOP_TIMEOUT,
                    pollInterval = READINESS_POLL_INTERVAL,
                ),
                readiness = { probeEchoReady() },
            )
    }

    @AfterAll
    fun stopPackagedApplication() {
        if (::packagedApplication.isInitialized) {
            packagedApplication.close()
        }
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

    protected fun post(
        path: String,
        body: ByteArray,
        timeout: Duration,
    ): HttpResponse<ByteArray> = send(path, body, "application/octet-stream", timeout)

    protected fun postText(
        path: String,
        body: String,
        timeout: Duration = REQUEST_TIMEOUT,
    ): HttpResponse<ByteArray> = send(path, body.toByteArray(), "text/plain; charset=UTF-8", timeout)

    protected fun get(
        path: String,
        timeout: Duration = REQUEST_TIMEOUT,
    ): HttpResponse<ByteArray> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(timeout)
                .GET()
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun send(
        path: String,
        body: ByteArray,
        contentType: String,
        timeout: Duration,
    ): HttpResponse<ByteArray> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(timeout)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    /** Waits for this instance's spool directory to drain; streaming suites use it as a cleanup oracle. */
    protected fun awaitStreamingCleanup() {
        val deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            if (streamingTempFiles().isEmpty()) return
            Thread.sleep(CLEANUP_RETRY_MILLIS)
        }
    }

    protected fun streamingTempFiles(): List<Path> =
        Files.list(fixtureTempDirectory).use { paths ->
            paths.filter { it.name.startsWith("muse-stream-") }.toList()
        }

    private fun assertPortAvailable() {
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
        val CLEANUP_TIMEOUT: Duration = Duration.ofSeconds(5)
        val PROCESS_STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READINESS_POLL_INTERVAL: Duration = Duration.ofMillis(100)
        const val CLEANUP_RETRY_MILLIS = 50L
    }
}

/** Streaming lifetime suite: the application runs with a configured streaming strategy. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PackagedStreamingE2ETestSupport : PackagedApplicationProcessSupport() {
    @Test
    fun `streams a multi-megabyte payload through the packaged listener request and response chain`() {
        val payload = ByteArray(2 * 1024 * 1024) { index -> ('a'.code + index % 26).toByte() }

        val response = post("/e2e/stream-proxy", payload, Duration.ofSeconds(20))

        assertEquals(200, response.statusCode())
        assertContentEquals(payload, response.body())
        awaitStreamingCleanup()
        assertEquals(emptyList(), streamingTempFiles())
    }

    @Test
    fun `releases streaming resources after a packaged flow failure`() {
        val payload = "failed-stream-".repeat(4096).toByteArray()

        val response = post("/e2e/stream-failure", payload, Duration.ofSeconds(10))

        assertTrue(response.statusCode() in 400..599)
        awaitStreamingCleanup()
        assertEquals(emptyList(), streamingTempFiles())
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackagedFileStoreStreamingE2ETest : PackagedStreamingE2ETestSupport() {
    override val instanceId: String = "repeatable-file-store"

    override fun applicationArguments(tempDirectory: Path): List<String> =
        listOf(
            "--muse.streaming.strategy=repeatable-file-store",
            "--muse.streaming.file-store.in-memory-size-bytes=4",
            "--muse.streaming.file-store.temp-directory=$tempDirectory",
            "--muse.streaming.file-store.delete-files-on-close=true",
        )
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackagedNonRepeatableStreamingE2ETest : PackagedStreamingE2ETestSupport() {
    override val instanceId: String = "non-repeatable"

    override fun applicationArguments(tempDirectory: Path): List<String> =
        listOf(
            "--muse.streaming.strategy=non-repeatable",
            "--muse.streaming.file-store.in-memory-size-bytes=4",
            "--muse.streaming.file-store.temp-directory=$tempDirectory",
            "--muse.streaming.file-store.delete-files-on-close=true",
        )
}

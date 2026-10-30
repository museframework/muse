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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackagedApplicationE2ETest {
    private val port = requiredProperty("packaged.e2e.port").toInt()
    private val applicationJar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
    private val applicationLog = Path.of(requiredProperty("packaged.e2e.log")).toAbsolutePath()
    private val client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()

    private lateinit var packagedApplication: PackagedApplication

    private lateinit var workingDirectory: Path

    @BeforeAll
    fun startPackagedApplication() {
        assertPortAvailable()
        workingDirectory = Files.createTempDirectory("muse-packaged-e2e-")

        packagedApplication =
            PackagedApplication.start(
                ProcessSpec(
                    jar = applicationJar,
                    log = applicationLog,
                    workingDirectory = workingDirectory,
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
                packagedApplication.close()
            }
        } finally {
            if (::workingDirectory.isInitialized) workingDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `packaged simple echo preserves request payload`() {
        val response = post("/e2e/echo", "hello")

        assertEquals(200, response.statusCode())
        assertEquals("Echo: hello", response.body())
    }

    @Test
    fun `packaged choice handles raised error`() {
        val response = post("/e2e/choice-error", "fail")

        assertEquals(200, response.statusCode())
        assertEquals("Choice error handled", response.body())
    }

    @Test
    fun `packaged choice otherwise branch succeeds`() {
        val response = post("/e2e/choice-error", "ok")

        assertEquals(200, response.statusCode())
        assertEquals("Choice success", response.body())
    }

    @Test
    fun `packaged foreach carries variables across all iterations`() {
        val response = post("/e2e/core/foreach", "input")

        assertEquals(200, response.statusCode())
        assertEquals("6", response.body())
    }

    @Test
    fun `packaged parallel foreach preserves payload and projects target`() {
        val response = post("/e2e/core/parallel-foreach", "input")

        assertEquals(200, response.statusCode())
        assertEquals("input:3", response.body())
    }

    @Test
    fun `packaged scatter gather preserves payload and projects target`() {
        val response = post("/e2e/core/scatter-gather", "input")

        assertEquals(200, response.statusCode())
        assertEquals("input:2", response.body())
    }

    @Test
    fun `packaged error handler executes its complete recovery pipeline`() {
        val response = post("/e2e/core/handler-steps", "input")

        assertEquals(200, response.statusCode())
        assertEquals("recovered:original", response.body())
    }

    @Test
    fun `packaged flow handler recovers outside a try without continuing the flow`() {
        val response = post("/e2e/core/try-flow-handler-boundary", "input")

        assertEquals(200, response.statusCode())
        assertEquals("flow-handler", response.body())
    }

    @Test
    fun `packaged cross-file flow reference invokes generated sub-flow`() {
        val response = post("/e2e/cross-file", "hello")

        assertEquals(200, response.statusCode())
        assertEquals("Cross-file: hello", response.body())
    }

    @Test
    fun `packaged listener outbound request returns downstream response`() {
        val response = post("/e2e/proxy", "request")

        assertEquals(200, response.statusCode())
        assertEquals("Outbound response", response.body())
    }

    @Test
    fun `packaged choice selects only its first matching branch`() {
        val response = post("/e2e/core/choice-first-match", "input")

        assertEquals(200, response.statusCode())
        assertEquals("first", response.body())
    }

    @Test
    fun `packaged choice without a match preserves caller state`() {
        val response = post("/e2e/core/choice-no-match", "input")

        assertEquals(200, response.statusCode())
        assertEquals("input:kept", response.body())
    }

    @Test
    fun `packaged choice predicate failure enters the expression handler`() {
        val response = post("/e2e/core/choice-predicate-error", "input")

        assertEquals(200, response.statusCode())
        assertEquals("EXPRESSION", response.body())
    }

    @Test
    fun `packaged foreach stops after a middle failure and exposes the completed prefix`() {
        val response = post("/e2e/core/foreach-middle-failure", "input")

        assertEquals(200, response.statusCode())
        assertEquals("middle:1", response.body())
    }

    @Test
    fun `packaged foreach keeps the tail batch and configured counters`() {
        val response = post("/e2e/core/foreach-batches", "input")

        assertEquals(200, response.statusCode())
        assertEquals("2:1|2:2|1:3|", response.body())
    }

    @Test
    fun `packaged nested foreach and choice retain the outer iteration state`() {
        val response = post("/e2e/core/nested-foreach-choice", "input")

        assertEquals(200, response.statusCode())
        assertEquals("1a1b2a2b", response.body())
    }

    @Test
    fun `packaged parallel foreach retains declaration ordered results`() {
        val response = post("/e2e/core/parallel-foreach-order", "input")

        assertEquals(200, response.statusCode())
        assertEquals("2,4,6", response.body())
    }

    @Test
    fun `packaged parallel foreach reports mixed child failures and timeouts`() {
        val response = post("/e2e/core/parallel-foreach-mixed", "input")

        assertEquals(200, response.statusCode())
        assertEquals("COMPOSITE_ROUTING:CHILD:TIMEOUT:ok", response.body())
    }

    @Test
    fun `packaged scatter gather retains declaration ordered results`() {
        val response = post("/e2e/core/scatter-gather-order", "input")

        assertEquals(200, response.statusCode())
        assertEquals("left,right", response.body())
    }

    @Test
    fun `packaged scatter gather preserves declaration order after right route completes first`() {
        val response = post("/e2e/core/scatter-gather-out-of-order", "input")

        assertEquals(200, response.statusCode())
        assertEquals("left,right", response.body())
    }

    @Test
    fun `packaged scatter gather reports a timeout for a slow route`() {
        val response = post("/e2e/core/scatter-gather-timeout", "input")

        assertEquals(200, response.statusCode())
        assertEquals("TIMEOUT", response.body())
    }

    @Test
    fun `packaged scatter gather timeout cancels a started route before its post-timeout action`() {
        val timeoutResponse = post("/e2e/core/scatter-gather-cancellation-probe", "input")

        assertEquals(200, timeoutResponse.statusCode())
        assertEquals("TIMEOUT", timeoutResponse.body())

        val probeResponse = post("/e2e/core/scatter-gather-cancellation-probe-state", "input")

        assertEquals(200, probeResponse.statusCode())
        assertEquals("started=true,cancelled=true,postTimeoutAction=false", probeResponse.body())
    }

    @Test
    fun `packaged scatter gather exposes one composite error to its parent handler`() {
        val response = post("/e2e/core/scatter-parent-any", "input")

        assertEquals(200, response.statusCode())
        assertEquals("COMPOSITE_ROUTING", response.body())
    }

    @Test
    fun `packaged flow local continue and parent failure use distinct handlers`() {
        val response = post("/e2e/core/flow-local-continue", "input")

        assertEquals(200, response.statusCode())
        assertEquals("child:parent", response.body())
    }

    @Test
    fun `packaged propagated flow failure retains child mutations for the parent`() {
        val response = post("/e2e/core/flow-propagate", "input")

        assertEquals(200, response.statusCode())
        assertEquals("child-payload:child:yes:child", response.body())
    }

    @Test
    fun `packaged failed targeted flow reference bypasses the target expression`() {
        val response = post("/e2e/core/flow-target-failure", "input")

        assertEquals(200, response.statusCode())
        assertEquals("target:existing", response.body())
    }

    @Test
    fun `packaged nested try propagation retains strategy mutations`() {
        val response = post("/e2e/core/nested-try-propagate", "input")

        assertEquals(200, response.statusCode())
        assertEquals("mutated:set:inner", response.body())
    }

    @Test
    fun `packaged conditional error handlers select the first matching predicate`() {
        val response = post("/e2e/core/conditional-handlers", "input")

        assertEquals(200, response.statusCode())
        assertEquals("second", response.body())
    }

    @Test
    fun `packaged secondary handler failure reaches the enclosing handler`() {
        val response = post("/e2e/core/secondary-handler-failure", "input")

        assertEquals(200, response.statusCode())
        assertEquals("SECONDARY", response.body())
    }

    @Test
    fun `packaged async branch reads its retained request input after the reply under the default strategy`() {
        try {
            val start = post("/policy/async/start", "synthetic-default-input", Duration.ofSeconds(10))

            assertEquals(202, start.statusCode())
            assertEquals("accepted", start.body())

            awaitProbeState("the accepted branch is entered and still blocked") { state ->
                state.startsWith("scenario=start;branchEntered=true;branchCompleted=false;read=none")
            }

            val release = post("/policy/async/release", "", Duration.ofSeconds(20))

            assertEquals(200, release.statusCode())
            assertEquals("start", probeField(release.body(), "scenario"))
            assertEquals("true", probeField(release.body(), "branchCompleted"))
            assertEquals("synthetic-default-input", probeField(release.body(), "read"))
        } finally {
            // The gate is never left closed on a failing assertion; the application is stopped anyway.
            runCatching { post("/policy/async/release", "", Duration.ofSeconds(20)) }
        }
    }

    private fun awaitProbeState(
        expectation: String,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = System.nanoTime() + PROBE_TIMEOUT.toNanos()
        var lastObservation = "no fixture state was observed"
        while (System.nanoTime() < deadline) {
            val response = get("/policy/async/state")
            assertEquals(200, response.statusCode())
            lastObservation = response.body()
            if (predicate(lastObservation)) return lastObservation
            Thread.sleep(PROBE_RETRY_MILLIS)
        }
        error("The fixture never reported that $expectation; last state: $lastObservation")
    }

    private fun probeField(
        state: String,
        name: String,
    ): String = state.substringAfter("$name=").substringBefore(';')

    private fun get(path: String): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:$port$path"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build()

        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
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

    private fun assertPortAvailable() {
        try {
            ServerSocket(port).use { socket -> socket.reuseAddress = true }
        } catch (failure: Exception) {
            throw IllegalStateException("Packaged E2E port $port is unavailable", failure)
        }
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Required system property is missing: $name" }

    companion object {
        private val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val PROBE_TIMEOUT: Duration = Duration.ofSeconds(15)
        private const val PROBE_RETRY_MILLIS = 25L
        private val PROCESS_STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val READINESS_POLL_INTERVAL: Duration = Duration.ofMillis(100)
    }
}

package dev.museframework.test.performance

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@QuarkusTest
@DisplayName("HTTP Listener Performance Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HttpListenerPerformanceTest {
    @field:TestHTTPResource("/api/hello")
    lateinit var endpoint: URI

    private lateinit var client: HttpClient

    @BeforeEach
    fun setUp() {
        client = HttpClient.newHttpClient()
    }

    @Test
    @Order(1)
    @DisplayName("Smoke: application responds within 10 seconds")
    fun `smoke test - application starts and responds`() {
        val startTime = System.currentTimeMillis()
        val status = get()
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(status == 200) { "Expected HTTP 200 but got $status" }
        assertTrue(elapsed < 10_000) {
            "First request took ${elapsed}ms, exceeds 10-second startup budget"
        }
        println("[SMOKE] First response in ${elapsed}ms — status: $status")
    }

    @Test
    @Order(2)
    @DisplayName("Load: 100 concurrent requests each respond within 2 seconds")
    fun `load test - 100 concurrent requests within 2 seconds`() {
        get()

        val concurrency = 100
        val executor = Executors.newFixedThreadPool(concurrency)
        val latch = CountDownLatch(concurrency)
        val responseTimes = CopyOnWriteArrayList<Long>()
        val errors = AtomicInteger(0)
        val overallStart = System.currentTimeMillis()

        repeat(concurrency) {
            executor.submit {
                try {
                    val start = System.currentTimeMillis()
                    val status = get()
                    responseTimes.add(System.currentTimeMillis() - start)
                    if (status != 200) errors.incrementAndGet()
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(30, TimeUnit.SECONDS)
        executor.shutdownNow()
        assertTrue(completed) { "Load test did not complete within 30 seconds" }

        val overallElapsed = System.currentTimeMillis() - overallStart
        val maxResponseTime = responseTimes.maxOrNull() ?: 0L
        val avgResponseTime = responseTimes.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val p95 =
            responseTimes.sorted().let { sorted ->
                if (sorted.isEmpty()) 0L else sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
            }

        println("[LOAD] Requests: $concurrency, Errors: ${errors.get()}")
        println("[LOAD] Avg: ${"%.1f".format(avgResponseTime)}ms, Max: ${maxResponseTime}ms, P95: ${p95}ms")
        println("[LOAD] Total wall time: ${overallElapsed}ms")

        assertTrue(errors.get() == 0) { "Expected 0 errors but got ${errors.get()}" }
        assertTrue(maxResponseTime < 2_000) {
            "Slowest request took ${maxResponseTime}ms, exceeds 2-second limit"
        }
    }

    @Test
    @Order(3)
    @DisplayName("Stress: 1000 requests with throughput greater than 50 req/s")
    fun `stress test - 1000 requests with acceptable throughput`() {
        get()

        val totalRequests = 1000
        val executor = Executors.newFixedThreadPool(50)
        val latch = CountDownLatch(totalRequests)
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val totalResponseTime = AtomicLong(0)
        val stressStart = System.currentTimeMillis()

        repeat(totalRequests) { index ->
            executor.submit {
                try {
                    val scheduledDelay = (index.toLong() * 10_000) / totalRequests
                    val elapsed = System.currentTimeMillis() - stressStart
                    val sleepTime = (scheduledDelay - elapsed).coerceAtLeast(0)
                    if (sleepTime > 0) Thread.sleep(sleepTime)

                    val start = System.currentTimeMillis()
                    val status = get()
                    totalResponseTime.addAndGet(System.currentTimeMillis() - start)
                    if (status == 200) successCount.incrementAndGet() else errorCount.incrementAndGet()
                } catch (_: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(60, TimeUnit.SECONDS)
        executor.shutdownNow()
        assertTrue(completed) { "Stress test did not complete within 60 seconds" }

        val stressElapsed = System.currentTimeMillis() - stressStart
        val throughput = successCount.get().toDouble() / stressElapsed * 1000.0
        val averageResponseTime =
            if (successCount.get() == 0) 0.0 else totalResponseTime.get().toDouble() / successCount.get()
        val errorRate = errorCount.get().toDouble() / totalRequests

        println("[STRESS] Success: ${successCount.get()}, Errors: ${errorCount.get()}")
        println("[STRESS] Duration: ${stressElapsed}ms, Throughput: ${"%.1f".format(throughput)} req/s")
        println("[STRESS] Avg response time: ${"%.1f".format(averageResponseTime)}ms")

        assertTrue(throughput > 50.0) {
            "Throughput ${"%.1f".format(throughput)} req/s is below 50 req/s minimum"
        }
        assertTrue(errorRate < 0.05) {
            "Error rate ${"%.1f".format(errorRate * 100)}% exceeds 5% threshold"
        }
    }

    @Test
    @Order(4)
    @DisplayName("Memory: heap usage stays within bounds during load")
    fun `memory test - heap usage under load`() {
        val runtime = Runtime.getRuntime()
        System.gc()
        Thread.sleep(200)

        val baselineUsed = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val peakUsed = AtomicLong(baselineUsed)
        val executor = Executors.newFixedThreadPool(200)
        val latch = CountDownLatch(200)

        repeat(200) {
            executor.submit {
                try {
                    get()
                    val used = runtime.totalMemory() - runtime.freeMemory()
                    peakUsed.updateAndGet { current -> maxOf(current, used) }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(30, TimeUnit.SECONDS)
        executor.shutdownNow()
        assertTrue(completed) { "Memory test did not complete within 30 seconds" }

        System.gc()
        Thread.sleep(200)
        val afterLoadUsed = runtime.totalMemory() - runtime.freeMemory()
        val memoryGrowthMb = (afterLoadUsed - baselineUsed).toDouble() / (1024 * 1024)
        val heapUtilization = peakUsed.get().toDouble() / maxMemory

        println("[MEMORY] Peak heap used: ${"%.1f".format(peakUsed.get().toDouble() / (1024 * 1024))}MB")
        println("[MEMORY] Memory growth during load: ${"%.1f".format(memoryGrowthMb)}MB")
        println("[MEMORY] Peak heap utilization: ${"%.1f".format(heapUtilization * 100)}%")

        assertTrue(heapUtilization < 0.90) {
            "Peak heap utilization ${"%.1f".format(heapUtilization * 100)}% exceeds 90% threshold"
        }
        assertTrue(memoryGrowthMb < 256) {
            "Memory grew by ${"%.1f".format(memoryGrowthMb)}MB during load, exceeds 256MB threshold"
        }
    }

    private fun get(): Int {
        val request = HttpRequest.newBuilder(endpoint).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}

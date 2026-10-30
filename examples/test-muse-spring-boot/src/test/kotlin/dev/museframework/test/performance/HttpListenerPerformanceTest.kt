package dev.museframework.test.performance

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.dsl.httpListenerConnection
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.springboot.MuseApplication
import dev.museframework.springboot.MuseConfigProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.WebApplicationType
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpResponse
import org.springframework.test.annotation.DirtiesContext
import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(
    classes = [PerformanceTestApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("HTTP Listener Performance Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HttpListenerPerformanceTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    @Qualifier("performanceRestTemplate")
    private lateinit var restTemplate: RestTemplate

    private lateinit var baseUrl: String

    @BeforeEach
    fun setUp() {
        baseUrl = "http://localhost:$port/api/hello"
    }

    @Test
    @DisplayName("Smoke: application starts and responds within 10 seconds")
    fun `smoke test - application starts and responds`() {
        val startTime = System.currentTimeMillis()

        val response = restTemplate.getForEntity(baseUrl, String::class.java)

        val elapsed = System.currentTimeMillis() - startTime
        assertTrue(response.statusCode == HttpStatus.OK) {
            "Expected HTTP 200 but got ${response.statusCode}"
        }
        assertTrue(elapsed < 10_000) {
            "First request took ${elapsed}ms, exceeds 10-second startup budget"
        }
        println("[SMOKE] First response in ${elapsed}ms — status: ${response.statusCode}")
    }

    @Test
    @DisplayName("Load: 100 concurrent requests each respond within 2 seconds")
    fun `load test - 100 concurrent requests within 2 seconds`() {
        restTemplate.getForEntity(baseUrl, String::class.java)

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
                    val response = restTemplate.getForEntity(baseUrl, String::class.java)
                    val duration = System.currentTimeMillis() - start
                    responseTimes.add(duration)
                    if (response.statusCode != HttpStatus.OK) {
                        errors.incrementAndGet()
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS)) {
            "Load test did not complete within 30 seconds"
        }
        executor.shutdown()

        val overallElapsed = System.currentTimeMillis() - overallStart
        val maxResponseTime = responseTimes.maxOrNull() ?: 0L
        val avgResponseTime = if (responseTimes.isNotEmpty()) responseTimes.average() else 0.0
        val p95 =
            responseTimes.sorted().let { sorted ->
                if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)] else 0L
            }

        println("[LOAD] Requests: $concurrency, Errors: ${errors.get()}")
        println("[LOAD] Avg: ${"%.1f".format(avgResponseTime)}ms, Max: ${maxResponseTime}ms, P95: ${p95}ms")
        println("[LOAD] Total wall time: ${overallElapsed}ms")

        assertTrue(errors.get() == 0) {
            "Expected 0 errors but got ${errors.get()}"
        }
        assertTrue(maxResponseTime < 2_000) {
            "Slowest request took ${maxResponseTime}ms, exceeds 2-second limit"
        }
    }

    @Test
    @DisplayName("Stress: 1000 requests with throughput > 50 req/s")
    fun `stress test - 1000 requests with acceptable throughput`() {
        restTemplate.getForEntity(baseUrl, String::class.java)

        val totalRequests = 1000
        val threadPoolSize = 50
        val executor = Executors.newFixedThreadPool(threadPoolSize)
        val latch = CountDownLatch(totalRequests)
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val totalResponseTime = AtomicLong(0)

        val stressStart = System.currentTimeMillis()

        repeat(totalRequests) { i ->
            executor.submit {
                try {
                    val delayMs = (i.toLong() * 10_000) / totalRequests // spread over ~10s
                    val elapsed = System.currentTimeMillis() - stressStart
                    val sleepTime = (delayMs - elapsed).coerceAtLeast(0)
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime)
                    }

                    val start = System.currentTimeMillis()
                    val response = restTemplate.getForEntity(baseUrl, String::class.java)
                    val duration = System.currentTimeMillis() - start
                    totalResponseTime.addAndGet(duration)

                    if (response.statusCode == HttpStatus.OK) {
                        successCount.incrementAndGet()
                    } else {
                        errorCount.incrementAndGet()
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS)) {
            "Stress test did not complete within 60 seconds"
        }
        executor.shutdown()

        val stressElapsed = System.currentTimeMillis() - stressStart
        val throughput = (successCount.get().toDouble() / stressElapsed) * 1000.0
        val avgResponseTime =
            if (successCount.get() > 0) {
                totalResponseTime.get().toDouble() / successCount.get()
            } else {
                0.0
            }

        println("[STRESS] Total requests: $totalRequests, Success: ${successCount.get()}, Errors: ${errorCount.get()}")
        println("[STRESS] Duration: ${stressElapsed}ms, Throughput: ${"%.1f".format(throughput)} req/s")
        println("[STRESS] Avg response time: ${"%.1f".format(avgResponseTime)}ms")

        assertTrue(throughput > 50.0) {
            "Throughput ${"%.1f".format(throughput)} req/s is below 50 req/s minimum"
        }
        val errorRate = errorCount.get().toDouble() / totalRequests
        assertTrue(errorRate < 0.05) {
            "Error rate ${"%.1f".format(errorRate * 100)}% exceeds 5% threshold"
        }
    }

    @Test
    @DisplayName("Memory: heap usage stays within bounds during load")
    fun `memory test - heap usage under load`() {
        val runtime = Runtime.getRuntime()

        System.gc()
        Thread.sleep(200)
        val baselineUsed = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()

        println("[MEMORY] Baseline heap used: ${baselineUsed / (1024 * 1024)}MB")
        println("[MEMORY] Max heap available: ${maxMemory / (1024 * 1024)}MB")

        val concurrency = 200
        val executor = Executors.newFixedThreadPool(concurrency)
        val latch = CountDownLatch(concurrency)
        var peakUsed = baselineUsed

        repeat(concurrency) {
            executor.submit {
                try {
                    restTemplate.getForEntity(baseUrl, String::class.java)
                    val used = runtime.totalMemory() - runtime.freeMemory()
                    synchronized(this) {
                        if (used > peakUsed) peakUsed = used
                    }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS)) {
            "Memory test did not complete within 30 seconds"
        }
        executor.shutdown()

        System.gc()
        Thread.sleep(200)
        val afterLoadUsed = runtime.totalMemory() - runtime.freeMemory()

        val memoryGrowthMb = (afterLoadUsed - baselineUsed).toDouble() / (1024 * 1024)
        val peakUsedMb = peakUsed.toDouble() / (1024 * 1024)
        val heapUtilization = peakUsed.toDouble() / maxMemory

        println("[MEMORY] Peak heap used: ${"%.1f".format(peakUsedMb)}MB")
        println("[MEMORY] After-load heap used: ${afterLoadUsed / (1024 * 1024)}MB")
        println("[MEMORY] Memory growth during load: ${"%.1f".format(memoryGrowthMb)}MB")
        println("[MEMORY] Peak heap utilization: ${"%.1f".format(heapUtilization * 100)}%")

        assertTrue(heapUtilization < 0.90) {
            "Peak heap utilization ${"%.1f".format(heapUtilization * 100)}% exceeds 90% threshold"
        }

        assertTrue(memoryGrowthMb < 256) {
            "Memory grew by ${"%.1f".format(memoryGrowthMb)}MB during load, exceeds 256MB threshold"
        }
    }
}

@MuseApplication(webEnvironment = WebApplicationType.SERVLET)
class PerformanceTestApp

@Configuration(proxyBeanMethods = false)
class PerformanceTestConfig : MuseConfigProvider {
    @Bean(name = ["performanceRestTemplate"])
    fun restTemplate(): RestTemplate {
        val restTemplate = RestTemplate()
        restTemplate.errorHandler =
            object : ResponseErrorHandler {
                override fun hasError(response: ClientHttpResponse): Boolean = false

                override fun handleError(
                    url: URI,
                    method: org.springframework.http.HttpMethod,
                    response: ClientHttpResponse,
                ) {}
            }
        return restTemplate
    }

    override fun flows(): List<MuseFlow> =
        listOf(
            flow("perf-hello-flow") {
                httpListener(
                    httpListenerConnection("testConnection") {
                        host = "0.0.0.0"
                        // port is managed by Spring test — uses random port
                    },
                ) {
                    path = "/api/hello"
                    allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST)
                    successResponse {
                        statusCode = "#[vars.httpStatus default 200]"
                    }
                }
                setVariable("httpStatus") {
                    docName = "Set HTTP Status"
                    value = "200"
                }
                setPayload { value = "\"hello world\"" }
            },
        )

    override fun configs(): List<MuseConfiguration> = emptyList()
}

package dev.museframework.connectors.http.spring

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.http.api.client.HttpClientInitializer
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.dsl.httpListenerConnection
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.springboot.MuseConfigProvider
import dev.museframework.springboot.MuseLifecycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext

@SpringBootTest(
    classes = [HttpConnectorSpringTestApp::class, HttpListenerSpringIT.ListenerTestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureTestRestTemplate
class HttpListenerSpringIT {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `should handle GET request through full listener flow`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$port/listener/greet",
            String::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
    }

    @Test
    fun `should handle POST request with JSON body through listener flow`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        val request = HttpEntity("""{"message": "hello"}""", headers)

        val response = restTemplate.postForEntity(
            "http://localhost:$port/listener/greet",
            request,
            String::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `should return 404 for path not registered in any flow`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$port/listener/does-not-exist",
            String::class.java,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `should handle multiple listener flows on different paths`() {
        val greetResponse = restTemplate.getForEntity(
            "http://localhost:$port/listener/greet",
            String::class.java,
        )
        assertEquals(HttpStatus.OK, greetResponse.statusCode)

        val statusResponse = restTemplate.getForEntity(
            "http://localhost:$port/listener/status",
            String::class.java,
        )
        assertEquals(HttpStatus.OK, statusResponse.statusCode)
    }

    @Test
    fun `should wire the official HTTP auto-configuration and registrar`() {
        val initializer = applicationContext.getBean(SpringHttpListenerServerInitializer::class.java)
        assertNotNull(initializer)
        assertTrue(initializer.servers().isNotEmpty())

        val clientInitializer = applicationContext.getBean(HttpClientInitializer::class.java)
        assertTrue(clientInitializer is SpringHttpClientInitializer)

        val registrars = applicationContext.getBeansOfType(MuseComponentRegistrar::class.java).values
        assertTrue(registrars.any { it is HttpConnectorRegistrar })

        val routerBeans = applicationContext.getBeansOfType(
            org.springframework.web.servlet.function.RouterFunction::class.java,
        )
        assertTrue(routerBeans.isNotEmpty())

        val lifecycle = applicationContext.getBean(MuseLifecycle::class.java)
        assertTrue(lifecycle.isRunning)
    }

    @Test
    fun `should pass query parameters through to handler`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$port/listener/greet?name=muse",
            String::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `should handle wildcard path matching in listener flow`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$port/listener/items/123",
            String::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    fun `should unregister listener routes when the Muse lifecycle stops`() {
        val lifecycle = applicationContext.getBean(MuseLifecycle::class.java)
        assertTrue(lifecycle.isRunning)

        lifecycle.stop()

        assertFalse(lifecycle.isRunning)
        val response =
            restTemplate.getForEntity(
                "http://localhost:$port/listener/greet",
                String::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ListenerTestConfig : MuseConfigProvider {

        private val connection = httpListenerConnection("test_connection") {
            host = "0.0.0.0"
        }

        override fun flows(): List<MuseFlow> = listOf(
            flow("greet-flow") {
                httpListener(connection) {
                    path = "/listener/greet"
                    allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST)
                    successResponse { statusCode = "#[vars.httpStatus default 200]" }
                }
                setVariable("httpStatus") { value = "200" }
                setPayload { value = "\"greeting response\"" }
            },
            flow("status-flow") {
                httpListener(connection) {
                    path = "/listener/status"
                    allowedMethods = listOf(HttpMethod.GET)
                    successResponse { statusCode = "#[vars.httpStatus default 200]" }
                }
                setVariable("httpStatus") { value = "200" }
                setPayload { value = "\"status: ok\"" }
            },
            flow("items-flow") {
                httpListener(connection) {
                    path = "/listener/items/*"
                    allowedMethods = listOf(HttpMethod.GET)
                    successResponse { statusCode = "#[vars.httpStatus default 200]" }
                }
                setVariable("httpStatus") { value = "200" }
                setPayload { value = "\"item details\"" }
            },
        )

        override fun configs(): List<MuseConfiguration> = emptyList()
    }
}

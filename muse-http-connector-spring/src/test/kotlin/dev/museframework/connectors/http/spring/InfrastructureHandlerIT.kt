package dev.museframework.connectors.http.spring

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.dsl.httpListenerConnection
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.infrastructure.InfrastructureAwareSource
import dev.museframework.engine.api.infrastructure.InfrastructureRequirement
import dev.museframework.engine.api.infrastructure.WebServerRequirement
import dev.museframework.engine.api.source.MuseEventEnvelope
import dev.museframework.springboot.MuseConfigProvider
import dev.museframework.springboot.infrastructure.MuseInfrastructureHandler
import kotlinx.coroutines.channels.SendChannel
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus

@SpringBootTest(
    classes = [HttpConnectorSpringTestApp::class, InfrastructureHandlerIT.InfraTestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureTestRestTemplate
class InfrastructureHandlerIT {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `should have MuseInfrastructureHandler bean in context`() {
        val handler = applicationContext.getBean(MuseInfrastructureHandler::class.java)
        assertNotNull(handler)
    }

    @Test
    fun `should detect InfrastructureAwareSource beans registered in Spring context`() {
        val sources = applicationContext.getBeansOfType(InfrastructureAwareSource::class.java)
        assertTrue(sources.isNotEmpty())
    }

    @Test
    fun `should detect WebServerRequirement from registered InfrastructureAwareSource`() {
        val sources = applicationContext.getBeansOfType(InfrastructureAwareSource::class.java).values
        val requirements = sources.flatMap { it.getInfrastructureRequirements() }

        assertTrue(requirements.any { it == WebServerRequirement })
    }

    @Test
    fun `should have web server running after infrastructure setup`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$port/infra/health",
            String::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `WebServerRequirement should have correct name and priority`() {
        assertEquals("web-server", WebServerRequirement.name)
        assertEquals(100, WebServerRequirement.priority)
    }

    @Test
    fun `should start application context successfully with HTTP listener infrastructure`() {
        assertTrue(applicationContext.containsBean("museInfrastructureHandler"))
        assertTrue(applicationContext.containsBean("testWebServerAwareSource"))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class InfraTestConfig : MuseConfigProvider {

        @Bean
        fun testWebServerAwareSource(): InfrastructureAwareSource = object : InfrastructureAwareSource {
            override fun getInfrastructureRequirements(): List<InfrastructureRequirement> =
                listOf(WebServerRequirement)

            override val docName: String = "TestSource"
            override val docId: String = "test-source"
            override val docDescription: String? = null
            override val sourceFile: String? = null
            override val sourceLine: Int? = null
            override val sourceClass: String? = null

            override suspend fun start(channel: SendChannel<MuseEventEnvelope>) {}
            override suspend fun stop() {}
            override suspend fun onResult(event: MuseEvent, transactionContext: Any?) {}
        }

        override fun flows(): List<MuseFlow> = listOf(
            flow("infra-health-flow") {
                httpListener(httpListenerConnection("test_connection") { host = "0.0.0.0" }) {
                    path = "/infra/health"
                    allowedMethods = listOf(HttpMethod.GET)
                    successResponse { statusCode = "#[vars.httpStatus default 200]" }
                }
                setVariable("httpStatus") { value = "200" }
                setPayload { value = "\"healthy\"" }
            },
        )

        override fun configs(): List<MuseConfiguration> = emptyList()
    }
}

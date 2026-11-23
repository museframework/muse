package dev.museframework.e2e.packaged

import dev.museframework.connectors.http.client.httpClientBuilder
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.e2e.packaged.generated.ConnectionsConfig
import dev.museframework.e2e.packaged.generated.OutboundRequestConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.http.api.client.HttpClientInitializer
import dev.museframework.http.api.config.ConnectionConfigRef
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.api.server.HttpListenerServerInitializer
import dev.museframework.http.api.server.HttpServer
import dev.museframework.http.api.source.httpListenerSourceBuilder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class GeneratedHttpConnectionBindingIT {
    @Test
    fun `listener resolves the same connection settings by Mule name and by direct reference`() = runBlocking {
        val connection = ConnectionsConfig().packagedListener
        val named = assertIs<HttpListenerConfig>(OutboundRequestConfig().packagedOutboundProxyFlow.source)
        assertIs<ConnectionConfigRef.ByName>(named.connectionConfigRef)
        val inline = named.copy(connectionConfigRef = ConnectionConfigRef.ByReference(connection))
        val namedContext = BindingContext().apply {
            properties["http.connection.${connection.name}"] = connection
        }

        val namedBinding = listenerBinding(named, namedContext)
        val inlineBinding = listenerBinding(inline, BindingContext())

        assertEquals("packaged-listener", namedBinding.name)
        assertEquals("0.0.0.0", namedBinding.host)
        assertEquals(0, namedBinding.port)
        assertEquals(namedBinding, inlineBinding)
    }

    @Test
    fun `request resolves the same connection settings by Mule name and by direct reference`() {
        val generated = OutboundRequestConfig()
        val connection = generated.httpBindingFixture
        val named = assertIs<HttpRequestConfig>(generated.httpRequest.pipeline.single())
        assertIs<ConfigRef.ByName>(named.connectionConfig)
        val inline = named.copy(connectionConfig = ConfigRef.ByReference(connection))
        val namedContext = BindingContext().apply {
            properties[connection.name] = connection
        }

        val namedBinding = requestBinding(named, namedContext)
        val inlineBinding = requestBinding(inline, BindingContext())

        assertEquals("http-binding-fixture", namedBinding.name)
        assertEquals("127.0.0.1", namedBinding.host)
        assertEquals(18080, namedBinding.port)
        val value = "value-\"-\$-\\\r\nnext"
        assertEquals(mapOf("header-\"-\$-\\" to value), namedBinding.defaultHeaders)
        assertEquals(mapOf("query-\"-\$-\\" to value), namedBinding.defaultQueryParams)
        assertEquals(namedBinding, inlineBinding)
    }

    // Capture the resolved configuration at the adapter initialization boundary.
    private suspend fun listenerBinding(
        component: HttpListenerConfig,
        context: MuseContext,
    ): HttpListenerConnectionConfig {
        val initializer = object : HttpListenerServerInitializer {
            override fun initialize(config: HttpListenerConnectionConfig): HttpServer = throw ListenerBinding(config)
        }
        val source = httpListenerSourceBuilder(initializer).build(component, context, context.expressionEvaluator)
        return assertFailsWith<ListenerBinding> { source.start(Channel()) }.config
    }

    private fun requestBinding(component: HttpRequestConfig, context: MuseContext): HttpRequestConnectionConfig {
        val initializer = object : HttpClientInitializer {
            override fun initialize(config: HttpRequestConnectionConfig): HttpClient = throw RequestBinding(config)
        }
        return assertFailsWith<RequestBinding> { httpClientBuilder(initializer).build(component, context) }.config
    }

    private class ListenerBinding(val config: HttpListenerConnectionConfig) : RuntimeException()

    private class RequestBinding(val config: HttpRequestConnectionConfig) : RuntimeException()

    private class BindingContext : MuseContext {
        override val properties = Properties()
        override val factory: MuseComponentFactory
            get() = error("Unexpected factory access while resolving an HTTP connection")
        override val flows = emptyMap<String, MuseFlow>()
        override val configs = emptyMap<String, MuseConfiguration>()
        override val expressionEvaluator = object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? =
                error("Unexpected expression evaluation while resolving an HTTP connection")
        }
    }
}

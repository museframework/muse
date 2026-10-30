package dev.museframework.babelfish.mappers.http

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.HttpSendBodyMode
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.config.SendCorrelationId
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("HttpRequestMapper")
class HttpRequestMapperTest {
    private fun mappingContext(): MappingContext = MappingContext(BabelfishRegistry(), Path.of("test.xml"))

    @Test
    fun `should map request connection metadata to the canonical model`() {
        val rawConfig =
            RawConfig(
                id = "internal-uuid",
                namespace = "http",
                tag = "request-config",
                attrs =
                    mapOf(
                        "name" to "HTTP_Request_config",
                        "doc:name" to "HTTP Request config",
                        "basePath" to "/v2",
                    ),
                children =
                    listOf(
                        RawElement(
                            namespace = "http",
                            tag = "request-connection",
                            attrs =
                                mapOf(
                                    "host" to "api.example.test",
                                    "port" to "8443",
                                    "protocol" to "HTTPS",
                                ),
                            children = emptyList(),
                            location = SourceLocation(2, 5),
                        ),
                    ),
                location = SourceLocation(1, 1),
            )

        val config = HttpRequestConnectionConfigMapper.map(rawConfig, mappingContext())

        assertEquals("HTTP_Request_config", config.name)
        assertEquals("HTTP Request config", config.docName)
        assertEquals("api.example.test", config.host)
        assertEquals(8443, config.port)
        assertEquals(HttpProtocol.HTTPS, config.protocol)
        assertEquals("/v2", config.basePath)
    }

    @Test
    fun `should map Mule request defaults and connection limits`() {
        val rawConfig =
            RawConfig(
                id = "request-config",
                namespace = "http",
                tag = "request-config",
                attrs =
                    mapOf(
                        "name" to "request-config",
                        "followRedirects" to "false",
                        "enableCookies" to "false",
                        "responseTimeout" to "45000",
                        "sendCorrelationId" to "NEVER",
                        "sendBodyMode" to "ALWAYS",
                        "requestStreamingMode" to "NEVER",
                    ),
                children =
                    listOf(
                        RawElement(
                            namespace = "http",
                            tag = "request-connection",
                            attrs =
                                mapOf(
                                    "host" to "api.example.test",
                                    "protocol" to "HTTPS",
                                    "usePersistentConnections" to "false",
                                    "maxConnections" to "8",
                                    "connectionIdleTimeout" to "12000",
                                ),
                            children = emptyList(),
                            location = SourceLocation(2, 5),
                        ),
                        keyValueGroup("default-headers", "default-header", "Accept", "application/json"),
                        keyValueGroup("default-query-params", "query-param", "locale", "en-GB"),
                    ),
                location = SourceLocation(1, 1),
            )

        val config = HttpRequestConnectionConfigMapper.map(rawConfig, mappingContext())

        assertEquals(443, config.port)
        assertFalse(config.followRedirects)
        assertFalse(config.enableCookies)
        assertFalse(config.usePersistentConnections)
        assertEquals(8, config.maxConnections)
        assertEquals(12000, config.connectionIdleTimeoutMs)
        assertEquals(45000, config.responseTimeoutMs)
        assertEquals(mapOf("Accept" to "application/json"), config.defaultHeaders)
        assertEquals(mapOf("locale" to "en-GB"), config.defaultQueryParams)
        assertEquals(SendCorrelationId.NEVER, config.sendCorrelationId)
        assertEquals(HttpSendBodyMode.ALWAYS, config.sendBodyMode)
        assertEquals(HttpStreamingMode.NEVER, config.requestStreamingMode)
    }

    @Test
    fun `should map request URL body maps and operation overrides`() {
        val rawElement =
            RawElement(
                namespace = "http",
                tag = "request",
                attrs =
                    mapOf(
                        "config-ref" to "HTTP_Request_config",
                        "url" to "https://api.example.test/orders/{id}",
                        "method" to "POST",
                        "responseTimeout" to "1000",
                        "followRedirects" to "false",
                        "sendBodyMode" to "ALWAYS",
                        "requestStreamingMode" to "NEVER",
                        "sendCorrelationId" to "ALWAYS",
                        "correlationId" to "#[vars.correlationId]",
                    ),
                children =
                    listOf(
                        expressionChild("body", "#[payload]"),
                        expressionChild("headers", "#[vars.headers]"),
                        expressionChild("query-params", "#[vars.query]"),
                        expressionChild("uri-params", "#[vars.uri]"),
                    ),
                location = SourceLocation(10, 5),
            )

        val request = HttpRequestMapper.map(rawElement, mappingContext())

        assertEquals("https://api.example.test/orders/{id}", request.url)
        assertEquals("#[payload]", request.body)
        assertEquals("#[vars.headers]", request.headersExpression)
        assertEquals("#[vars.query]", request.queryParamsExpression)
        assertEquals("#[vars.uri]", request.uriParamsExpression)
        assertEquals(1000, request.responseTimeoutMs)
        assertFalse(request.followRedirects!!)
        assertEquals(HttpSendBodyMode.ALWAYS, request.sendBodyMode)
        assertEquals(HttpStreamingMode.NEVER, request.requestStreamingMode)
        assertEquals(SendCorrelationId.ALWAYS, request.sendCorrelationId)
        assertEquals("#[vars.correlationId]", request.correlationId)

        val code = HttpRequestCodeEmitter.emit(request, CodegenContext("com.example"))
        assertTrue(code.contains("url = \"https://api.example.test/orders/{id}\""))
        assertTrue(code.contains("headersExpression = \"#[vars.headers]\""))
        assertTrue(code.contains("sendBodyMode = HttpSendBodyMode.ALWAYS"))
    }

    @Test
    fun `should preserve HTTP response target and target value`() {
        val rawElement = RawElement(
            namespace = "http",
            tag = "request",
            attrs = mapOf(
                "config-ref" to "receiver",
                "target" to "httpReply",
                "targetValue" to "#[message]",
            ),
            children = emptyList(),
            location = SourceLocation(10, 5),
        )

        val request = HttpRequestMapper.map(rawElement, mappingContext())
        val code = HttpRequestCodeEmitter.emit(request, CodegenContext("com.example"))

        assertEquals(TargetConfig("httpReply", "#[message]"), request.targetConfig)
        assertTrue(code.contains("targetConfig = TargetConfig(\"httpReply\", \"#[{payload: payload, attributes: attributes}]\")"))
    }

    @Test
    fun `should preserve named config reference path and method`() {
        val rawElement =
            RawElement(
                namespace = "http",
                tag = "request",
                attrs =
                    mapOf(
                        "config-ref" to "HTTP_Request_config",
                        "path" to "/orders",
                        "method" to "POST",
                    ),
                children = emptyList(),
                location = SourceLocation(10, 5),
            )

        val request = HttpRequestMapper.map(rawElement, mappingContext())

        val configRef = request.connectionConfig as ConfigRef.ByName
        assertEquals("HTTP_Request_config", configRef.name)
        assertEquals("/orders", request.path)
        assertEquals(HttpMethod.POST, request.method)
    }

    @Test
    fun `should emit protocol default request port`() {
        val connection =
            HttpRequestConnectionConfig(
                name = "secure-api",
                host = "api.example.test",
                protocol = HttpProtocol.HTTPS,
                port = 443,
            )

        val code = HttpRequestConnectionConfigCodeEmitter.emit(connection, CodegenContext("com.example"))

        assertTrue(code.contains("port = 443"))
    }

    @Test
    fun `should preserve request connection property placeholders`() {
        val rawConfig = RawConfig(
            id = "receiver",
            namespace = "http",
            tag = "request-config",
            attrs = mapOf("name" to "receiver"),
            children = listOf(
                RawElement(
                    namespace = "http",
                    tag = "request-connection",
                    attrs = mapOf("host" to "\${receiver.host}", "port" to "\${receiver.target.port}"),
                    children = emptyList(),
                    location = SourceLocation(2, 5),
                ),
            ),
            location = SourceLocation(1, 1),
        )

        val connection = HttpRequestConnectionConfigMapper.map(rawConfig, mappingContext())
        val code = HttpRequestConnectionConfigCodeEmitter.emit(connection, CodegenContext("com.example"))

        assertEquals("\${receiver.target.port}", connection.portExpression)
        assertTrue(code.contains("portExpression = \"\\\${receiver.target.port}\""))
    }

    @Test
    fun `should emit only canonical HTTP API references`() {
        val connection =
            HttpRequestConnectionConfig(
                name = "HTTP_Request_config",
                docName = "HTTP Request config",
                host = "api.example.test",
                port = 8443,
                protocol = HttpProtocol.HTTPS,
                basePath = "/v2",
            )
        val connectionContext = CodegenContext("com.example")
        val connectionCode = HttpRequestConnectionConfigCodeEmitter.emit(connection, connectionContext)

        val request =
            HttpRequestConfig(
                connectionConfig = ConfigRef.ByName("HTTP_Request_config"),
                path = "/orders",
                method = HttpMethod.POST,
            )
        val requestContext = CodegenContext("com.example")
        val requestCode = HttpRequestCodeEmitter.emit(request, requestContext)

        assertTrue(connectionCode.contains("name = \"HTTP_Request_config\""))
        assertTrue(connectionCode.contains("docName = \"HTTP Request config\""))
        assertTrue(requestCode.contains("httpRequest(httpRequestConnection(\"HTTP_Request_config\"))"))
        assertTrue(requestCode.contains("method = HttpMethod.POST"))

        val imports = connectionContext.resolvedImports() + requestContext.resolvedImports()
        assertTrue(imports.contains("dev.museframework.http.api.dsl.httpRequestConnection"))
        assertTrue(imports.contains("dev.museframework.http.api.dsl.httpRequest"))
        assertFalse(imports.any { it.startsWith("dev.museframework.connectors.http") })
    }

    private fun keyValueGroup(
        groupTag: String,
        itemTag: String,
        key: String,
        value: String,
    ): RawElement =
        RawElement(
            namespace = "http",
            tag = groupTag,
            attrs = emptyMap(),
            children =
                listOf(
                    RawElement(
                        namespace = "http",
                        tag = itemTag,
                        attrs = mapOf("key" to key, "value" to value),
                        children = emptyList(),
                        location = SourceLocation(3, 9),
                    ),
                ),
            location = SourceLocation(3, 5),
        )

    private fun expressionChild(
        tag: String,
        expression: String,
    ): RawElement =
        RawElement(
            namespace = "http",
            tag = tag,
            attrs = emptyMap(),
            children = emptyList(),
            location = SourceLocation(11, 9),
            textContent = expression,
        )
}

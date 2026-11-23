package dev.museframework.babelfish.mappers.http

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.parser.MuleXmlParser
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.http.api.config.ConnectionConfigRef
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.HttpSendBodyMode
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.config.SendCorrelationId
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpTranslationIT {
    @Test
    fun `translates and emits every HTTP provider component category`() {
        val provider = HttpMapperProvider()
        val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
        val result =
            MuseBabelfish.translateXmlToMuseKotlinDsl(
                Path.of("src/test/resources/mule/http/http-listener-request.xml"),
                registry,
            )

        val listenerConnection =
            assertIs<HttpListenerConnectionConfig>(result.usedConfigs.getValue("HTTP_Listener_config"))
        assertEquals("HTTP Listener config", listenerConnection.docName)
        assertEquals("0.0.0.0", listenerConnection.host)
        assertEquals(8081, listenerConnection.port)
        assertEquals(HttpProtocol.HTTP, listenerConnection.protocol)
        assertEquals("/service", listenerConnection.basePath)
        assertFalse(listenerConnection.usePersistentConnections)
        assertEquals(45000L, listenerConnection.connectionIdleTimeoutMs)
        assertEquals(35000L, listenerConnection.readTimeoutMs)

        val requestConnection =
            assertIs<HttpRequestConnectionConfig>(result.usedConfigs.getValue("HTTP_Request_config"))
        assertEquals("HTTP Request config", requestConnection.docName)
        assertEquals("api.example.test", requestConnection.host)
        assertEquals(8443, requestConnection.port)
        assertEquals(HttpProtocol.HTTPS, requestConnection.protocol)
        assertEquals("/v2", requestConnection.basePath)
        assertEquals(12000L, requestConnection.connectionIdleTimeoutMs)
        assertEquals(45000L, requestConnection.responseTimeoutMs)
        assertFalse(requestConnection.followRedirects)
        assertFalse(requestConnection.usePersistentConnections)
        assertEquals(4, requestConnection.maxConnections)
        assertFalse(requestConnection.enableCookies)
        val escapedValue = "value-\"-\$-\\\r\nnext"
        assertEquals(
            mapOf("Accept" to "application/json", "header-\"-\$-\\" to escapedValue),
            requestConnection.defaultHeaders,
        )
        assertEquals(
            mapOf("locale" to "en", "query-\"-\$-\\" to escapedValue),
            requestConnection.defaultQueryParams,
        )
        assertEquals(SendCorrelationId.NEVER, requestConnection.sendCorrelationId)
        assertEquals(HttpSendBodyMode.NEVER, requestConnection.sendBodyMode)
        assertEquals(HttpStreamingMode.NEVER, requestConnection.requestStreamingMode)

        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val source = assertIs<HttpListenerConfig>(flow.source)
        UUID.fromString(source.docId)
        val listenerRef = assertIs<ConnectionConfigRef.ByName>(source.connectionConfigRef)
        assertEquals("HTTP_Listener_config", listenerRef.name)
        assertEquals("/api/proxy/{id}", source.path)
        assertEquals(listOf(HttpMethod.POST), source.allowedMethods)
        assertEquals(HttpStreamingMode.ALWAYS, source.responseStreamingMode)
        assertEquals("#[vars.status default 201]", source.successResponse.statusCode)
        assertEquals("#[vars.reason default 'Created']", source.successResponse.reasonPhrase)
        assertEquals("#[vars.responseHeaders]", source.successResponse.headersExpression)
        assertEquals("#[payload]", source.successResponse.body)
        assertEquals("#[vars.status default 500]", source.errorResponse.statusCode)
        assertEquals("#[error.description]", source.errorResponse.reasonPhrase)
        assertEquals("#[vars.errorHeaders]", source.errorResponse.headersExpression)
        assertEquals("#[error.description]", source.errorResponse.body)

        val request = assertIs<HttpRequestConfig>(flow.pipeline.single())
        UUID.fromString(request.docId)
        val requestRef = assertIs<ConfigRef.ByName>(request.connectionConfig)
        assertEquals("HTTP_Request_config", requestRef.name)
        assertEquals("/orders/{id}", request.path)
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("#[payload]", request.body)
        assertEquals("#[vars.headers]", request.headersExpression)
        assertEquals("#[vars.queryParams]", request.queryParamsExpression)
        assertEquals("#[vars.uriParams]", request.uriParamsExpression)
        assertEquals(2000L, request.responseTimeoutMs)
        assertEquals(true, request.followRedirects)
        assertEquals(HttpSendBodyMode.ALWAYS, request.sendBodyMode)
        assertEquals(HttpStreamingMode.ALWAYS, request.requestStreamingMode)
        assertEquals(SendCorrelationId.ALWAYS, request.sendCorrelationId)
        assertEquals("#[correlationId]", request.correlationId)
        assertEquals(TargetConfig("httpReply", "#[message]"), request.targetConfig)

        val code =
            KotlinCodeEmitter()
                .apply { provider.registerEmitters(this) }
                .emit(result, "dev.museframework.babelfish.generated")

        assertContains(code, "import dev.museframework.dsl.api.config.TargetConfig")
        assertContains(code, "import dev.museframework.http.api.model.HttpProtocol")
        assertContains(code, "import dev.museframework.http.api.dsl.httpListener\n")
        assertContains(code, "import dev.museframework.http.api.dsl.httpListenerConnection")
        assertContains(code, "import dev.museframework.http.api.dsl.httpRequest\n")
        assertContains(code, "import dev.museframework.http.api.dsl.httpRequestConnection")
        assertContains(code, "httpListener(\"HTTP_Listener_config\")")
        assertContains(code, "allowedMethods = listOf(HttpMethod.POST)")
        assertContains(code, "path = \"/api/proxy/{id}\"")
        assertContains(code, "responseStreamingMode = HttpStreamingMode.ALWAYS")
        assertContains(code, "successResponse {")
        assertContains(code, "reasonPhrase = \"#[vars.reason default 'Created']\"")
        assertContains(code, "headersExpression = \"#[vars.responseHeaders]\"")
        assertContains(code, "errorResponse {")
        assertContains(code, "httpRequest(httpRequestConnection(\"HTTP_Request_config\"))")
        assertContains(code, "method = HttpMethod.POST")
        assertContains(code, "basePath = \"/v2\"")
        assertContains(code, "defaultHeader(\"Accept\", \"application/json\")")
        assertContains(code, "defaultQueryParam(\"locale\", \"en\")")
        assertContains(code, "path = \"/orders/{id}\"")
        assertContains(code, "body = \"#[payload]\"")
        assertContains(code, "uriParamsExpression = \"#[vars.uriParams]\"")
        assertContains(code, "sendBodyMode = HttpSendBodyMode.ALWAYS")
        assertContains(code, "requestStreamingMode = HttpStreamingMode.ALWAYS")
        assertFalse(code.contains("dev.museframework.connectors.http"))
    }

    @Test
    fun `preserves explicit XML ids on the listener and same-type requests`() {
        val sourceFile = Path.of("src/test/resources/mule/http/http-component-identities.xml")
        val rawFlow = MuleXmlParser().parse(sourceFile).flows.single()
        val rawListener = requireNotNull(rawFlow.source)
        val rawRequests = rawFlow.pipeline
        val escapedRequestId = "http-request-\"second\"-\$"

        assertEquals("http-identity-flow", rawFlow.name)
        assertEquals("http-flow-original", rawFlow.docId)
        assertEquals("http-listener-original", rawListener.attrs["doc:id"])
        assertEquals(listOf("http-request-first", escapedRequestId), rawRequests.map { it.attrs["doc:id"] })
        assertEquals("HTTP Listener", rawListener.attrs["doc:name"])
        assertEquals(listOf("HTTP first request", "HTTP second request"), rawRequests.map { it.attrs["doc:name"] })
        assertEquals(14, rawListener.location.line)
        assertEquals(listOf(16, 18), rawRequests.map { it.location.line })

        val provider = HttpMapperProvider()
        val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
        val result = MuseBabelfish.translateXmlToMuseKotlinDsl(sourceFile, registry)
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val listener = assertIs<HttpListenerConfig>(flow.source)
        val requests = flow.pipeline.map { assertIs<HttpRequestConfig>(it) }

        assertEquals("http-flow-original", flow.docId)
        assertEquals("http-listener-original", listener.docId)
        assertEquals(listOf("http-request-first", escapedRequestId), requests.map { it.docId })
        assertEquals("/", listener.path)
        assertEquals(listOf("/first", "/second"), requests.map { it.path })
        assertEquals(listOf(HttpMethod.POST, HttpMethod.PATCH), requests.map { it.method })
        assertEquals("HTTP_Listener_config", assertIs<ConnectionConfigRef.ByName>(listener.connectionConfigRef).name)
        assertEquals(
            listOf("HTTP_Request_config", "HTTP_Request_config"),
            requests.map { assertIs<ConfigRef.ByName>(it.connectionConfig).name },
        )
        assertEquals(
            "HTTP Listener config",
            assertIs<HttpListenerConnectionConfig>(result.usedConfigs.getValue("HTTP_Listener_config")).docName,
        )
        assertEquals(
            "HTTP Request config",
            assertIs<HttpRequestConnectionConfig>(result.usedConfigs.getValue("HTTP_Request_config")).docName,
        )

        val code = KotlinCodeEmitter().apply { provider.registerEmitters(this) }
            .emit(result, "dev.museframework.babelfish.generated")
        val listenerCall = code.indexOf("httpListener(\"HTTP_Listener_config\")")
        val requestCall = "httpRequest(httpRequestConnection(\"HTTP_Request_config\"))"
        val firstRequestCall = code.indexOf(requestCall, listenerCall)
        val secondRequestCall = code.indexOf(requestCall, firstRequestCall + requestCall.length)

        assertTrue(listenerCall >= 0)
        assertTrue(firstRequestCall > listenerCall)
        assertTrue(secondRequestCall > firstRequestCall)
        assertContains(code.substring(listenerCall, firstRequestCall), "docId = \"http-listener-original\"")
        assertContains(code.substring(firstRequestCall, secondRequestCall), "docId = \"http-request-first\"")
        assertContains(
            code.substring(secondRequestCall),
            "docId = ${kotlinStringLiteral(escapedRequestId)}",
        )

        val regenerated = MuseBabelfish.translateXmlToMuseKotlinDsl(sourceFile, registry)
        val regeneratedCode = KotlinCodeEmitter().apply { provider.registerEmitters(this) }
            .emit(regenerated, "dev.museframework.babelfish.generated")
        assertEquals(code, regeneratedCode)
    }
}

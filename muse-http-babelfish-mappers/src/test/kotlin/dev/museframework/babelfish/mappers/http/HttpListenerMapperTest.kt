package dev.museframework.babelfish.mappers.http

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.http.api.config.ConnectionConfigRef
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpStreamingMode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("HttpListenerMapper")
class HttpListenerMapperTest {
    private fun createMappingContext(): MappingContext = MappingContext(BabelfishRegistry(), Path.of("test.xml"))

    @Test
    fun `should extract name attribute from XML config`() {
        // Given
        val rawConfig =
            RawConfig(
                id = "internal-uuid",
                namespace = "http",
                tag = "listener-config",
                attrs =
                    mapOf(
                        "name" to "HTTP_Listener_config",
                        "doc:name" to "HTTP Listener config",
                        "basePath" to "/service",
                    ),
                children =
                    listOf(
                        RawElement(
                            namespace = "http",
                            tag = "listener-connection",
                            attrs =
                                mapOf(
                                    "host" to "0.0.0.0",
                                    "port" to "8081",
                                    "connectionIdleTimeout" to "45000",
                                    "readTimeout" to "35000",
                                    "usePersistentConnections" to "false",
                                ),
                            children = emptyList(),
                            location = SourceLocation(1, 1),
                        ),
                    ),
                location = SourceLocation(1, 1),
            )

        // When
        val config = HttpListenerConnectionConfigMapper.map(rawConfig, createMappingContext())

        assertEquals("HTTP_Listener_config", config.name)
        assertEquals("HTTP Listener config", config.docName)
        assertEquals("/service", config.basePath)
        assertEquals(45000L, config.connectionIdleTimeoutMs)
        assertEquals(35000L, config.readTimeoutMs)
        assertFalse(config.usePersistentConnections)
    }

    @Test
    fun `should generate code with config name`() {
        // Given
        val config =
            HttpListenerConnectionConfig(
                name = "HTTP_Listener_config",
                docName = "HTTP Listener config",
                host = "0.0.0.0",
                port = 8081,
                basePath = "/service",
                usePersistentConnections = false,
            )
        val ctx = CodegenContext("com.test")

        // When
        val code = HttpListenerConnectionConfigCodeEmitter.emit(config, ctx)

        // Then
        // Note: ConfigCodeEmitter returns code WITHOUT val prefix - prefix is added by KotlinCodeEmitter
        assertTrue(code.contains("httpListenerConnection(\"HTTP_Listener_config\")"))
        assertTrue(code.contains("host = \"0.0.0.0\""))
        assertTrue(code.contains("port = 8081"))
        assertTrue(code.contains("basePath = \"/service\""))
        assertTrue(code.contains("usePersistentConnections = false"))
    }

    @Test
    fun `should preserve config-ref for lookup`() {
        // Given
        val rawElement =
            RawElement(
                namespace = "http",
                tag = "listener",
                attrs =
                    mapOf(
                        "config-ref" to "HTTP_Listener_config",
                        "path" to "/api/*",
                    ),
                children = emptyList(),
                location = SourceLocation(10, 5),
            )

        // When
        val source = HttpListenerMapper.map(rawElement, createMappingContext())

        // Then
        val ref = source.connectionConfigRef as ConnectionConfigRef.ByName
        assertEquals("HTTP_Listener_config", ref.name)
    }

    @Test
    fun `should map and emit listener response parameters`() {
        val rawElement =
            RawElement(
                namespace = "http",
                tag = "listener",
                attrs =
                    mapOf(
                        "config-ref" to "HTTP_Listener_config",
                        "path" to "/api/{id}",
                        "allowedMethods" to "GET, POST",
                        "responseStreamingMode" to "ALWAYS",
                    ),
                children =
                    listOf(
                        responseElement(
                            tag = "response",
                            statusCode = "#[vars.status default 201]",
                            reasonPhrase = "#[vars.reason]",
                            headers = "#[vars.headers]",
                            body = "#[payload]",
                        ),
                        responseElement(
                            tag = "error-response",
                            statusCode = "#[vars.status default 500]",
                            reasonPhrase = "#[error.description]",
                            headers = "#[vars.errorHeaders]",
                            body = "#[error.description]",
                        ),
                    ),
                location = SourceLocation(10, 5),
            )

        val source = HttpListenerMapper.map(rawElement, createMappingContext())

        assertEquals(HttpStreamingMode.ALWAYS, source.responseStreamingMode)
        assertEquals("#[vars.reason]", source.successResponse.reasonPhrase)
        assertEquals("#[vars.headers]", source.successResponse.headersExpression)
        assertEquals("#[payload]", source.successResponse.body)
        assertEquals("#[error.description]", source.errorResponse.reasonPhrase)

        val code = HttpListenerCodeEmitter.emit(source, CodegenContext("com.test"))
        assertTrue(code.contains("responseStreamingMode = HttpStreamingMode.ALWAYS"))
        assertTrue(code.contains("successResponse {"))
        assertTrue(code.contains("headersExpression = \"#[vars.headers]\""))
        assertTrue(code.contains("errorResponse {"))
    }

    private fun responseElement(
        tag: String,
        statusCode: String,
        reasonPhrase: String,
        headers: String,
        body: String,
    ): RawElement =
        RawElement(
            namespace = "http",
            tag = tag,
            attrs = mapOf("statusCode" to statusCode, "reasonPhrase" to reasonPhrase),
            children =
                listOf(
                    expressionElement("headers", headers),
                    expressionElement("body", body),
                ),
            location = SourceLocation(11, 9),
        )

    private fun expressionElement(
        tag: String,
        expression: String,
    ): RawElement =
        RawElement(
            namespace = "http",
            tag = tag,
            attrs = emptyMap(),
            children = emptyList(),
            textContent = expression,
            location = SourceLocation(12, 13),
        )
}

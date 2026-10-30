package dev.museframework.connectors.http.processor

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.HttpSendBodyMode
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.config.SendCorrelationId
import dev.museframework.http.api.model.HttpErrorType
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.model.HttpResponseAttributes
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.api.processor.HttpOutboundRequest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.stream.Stream
import javax.net.ssl.SSLException

class HttpRequestProcessorContractIT {
    private val connectionConfig =
        HttpRequestConnectionConfig(
            name = "orders-api",
            docName = "Orders API",
            host = "api.example.test",
            port = 8443,
            protocol = HttpProtocol.HTTPS,
            basePath = "/v2/",
            followRedirects = true,
        )

    @Test
    fun `should translate an event into an outbound request and expose the response`() =
        runTest {
            val responseBody = """{"id":42,"status":"accepted"}""".toByteArray()
            val client =
                RecordingHttpClient(
                    response =
                        HttpResponse(
                            statusCode = 201,
                            headers =
                                mapOf(
                                    "Content-Type" to TypedValue.MIME_JSON,
                                    "X-Request-ID" to "request-42",
                                ),
                            body = responseBody,
                            reasonPhrase = "Created",
                        ),
                )
            val evaluator =
                StubExpressionEvaluator(
                    mapOf(
                        "#[vars.resource]" to "/orders/42",
                        "#[vars.token]" to "Bearer contract-token",
                        "#[vars.expand]" to "items",
                        "#[vars.body]" to """{"quantity":2}""",
                    ),
                )
            val requestConfig =
                requestConfig(
                    path = "#[vars.resource]",
                    method = HttpMethod.POST,
                    headers =
                        mapOf(
                            "Authorization" to "#[vars.token]",
                            "Content-Type" to TypedValue.MIME_JSON,
                        ),
                    queryParams = mapOf("expand" to "#[vars.expand]"),
                    body = "#[vars.body]",
                    responseTimeoutMs = 1_500,
                    followRedirects = false,
                    sendCorrelationId = SendCorrelationId.ALWAYS,
                )
            val processor = processor(requestConfig, client, evaluator)

            val result =
                assertInstanceOf(
                    ProcessorResult.Success::class.java,
                    processor.process(context(evaluator, correlationId = "correlation-42")),
                )

            val outbound = checkNotNull(client.recordedRequest)
            assertEquals(HttpMethod.POST, outbound.method)
            assertEquals("https://api.example.test:8443/v2/orders/42", outbound.url)
            assertEquals("Bearer contract-token", outbound.headers["Authorization"])
            assertEquals(TypedValue.MIME_JSON, outbound.headers["Content-Type"])
            assertEquals("correlation-42", outbound.headers["X-Correlation-ID"])
            assertEquals(mapOf("expand" to "items"), outbound.queryParams)
            assertArrayEquals("""{"quantity":2}""".toByteArray(), outbound.body)
            assertEquals(1_500, outbound.responseTimeoutMs)
            assertEquals(false, outbound.followRedirects)

            val responseEvent = result.context.event
            assertEquals("""{"id":42,"status":"accepted"}""", responseEvent.payload?.getOrNull())
            assertEquals(TypedValue.MIME_JSON, responseEvent.payload?.mimeType)
            val attributes = responseEvent.attributes?.getOrNull() as HttpResponseAttributes
            assertEquals(201, attributes.statusCode)
            assertEquals("request-42", attributes.headers["X-Request-ID"])
            assertEquals("Created", attributes.reasonPhrase)
        }

    @Test
    fun `should project a successful response into the target variable`() =
        runTest {
            val client = RecordingHttpClient(HttpResponse(statusCode = 204))
            val evaluator = StubExpressionEvaluator(mapOf("#[payload]" to "stored-payload"))
            val targetConfig = TargetConfig.of("storedResponse", "#[payload]")
            val requestConfig = requestConfig(targetConfig = targetConfig)
            val callerEvent = context(evaluator).event

            val result =
                assertInstanceOf(
                    ProcessorResult.Success::class.java,
                    processor(requestConfig, client, evaluator).process(context(evaluator)),
                )

            val projected = result.context.event
            assertEquals("stored-payload", projected.variables["storedResponse"]?.value)
            assertEquals(callerEvent.payload, projected.payload, "Projection keeps the caller's payload")
            assertNull(projected.error)
        }

    @Test
    fun `should turn validator rejection into a typed HTTP failure`() =
        runTest {
            val responseBody = """{"error":"missing"}""".toByteArray()
            val client = RecordingHttpClient(HttpResponse(statusCode = 404, body = responseBody))
            val evaluator = StubExpressionEvaluator()

            val result =
                assertInstanceOf(
                    ProcessorResult.Failure::class.java,
                    processor(requestConfig(path = "/orders/404"), client, evaluator)
                        .process(context(evaluator)),
                )

            assertEquals(HttpErrorType.NOT_FOUND, result.error.errorType)
            assertEquals("HTTP 404", result.error.description)
            assertEquals("""{"error":"missing"}""", result.error.errorMessage)
            assertSame(result.error, result.context.event.error)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transportFailures")
    fun `should classify transport failures without an adapter`(
        caseName: String,
        failure: Exception,
        expectedErrorType: dev.museframework.dsl.api.model.error.ErrorType,
    ) = runTest {
        val client = RecordingHttpClient(failure = failure)
        val evaluator = StubExpressionEvaluator()

        val result =
            assertInstanceOf(
                ProcessorResult.Failure::class.java,
                processor(requestConfig(), client, evaluator).process(context(evaluator)),
            )

        assertEquals(expectedErrorType, result.error.errorType, caseName)
        assertSame(failure, result.error.cause)
    }

    @Test
    fun `should resolve Mule-compatible request parameters and configuration defaults`() =
        runTest {
            val configuredConnection =
                connectionConfig.copy(
                    host = "",
                    defaultHeaders =
                        mapOf(
                            "Accept" to "application/json",
                            "X-Override" to "default",
                        ),
                    defaultQueryParams = mapOf("locale" to "en-GB"),
                    sendCorrelationId = SendCorrelationId.NEVER,
                )
            val evaluator =
                StubExpressionEvaluator(
                    mapOf(
                        "#[vars.url]" to "https://api.example.test/orders/{id}",
                        "#[vars.headers]" to mapOf("X-Expression" to "yes", "X-Override" to "expression"),
                        "#[vars.query]" to mapOf("expand" to "items"),
                        "#[vars.uri]" to mapOf("id" to "42"),
                        "#[vars.correlation]" to "order-42",
                    ),
                )
            val client = RecordingHttpClient(HttpResponse(statusCode = 204))
            val request =
                requestConfig(
                    url = "#[vars.url]",
                    headers = mapOf("X-Override" to "operation"),
                    headersExpression = "#[vars.headers]",
                    queryParams = mapOf("page" to "2"),
                    queryParamsExpression = "#[vars.query]",
                    uriParamsExpression = "#[vars.uri]",
                    sendCorrelationId = SendCorrelationId.ALWAYS,
                    correlationId = "#[vars.correlation]",
                    requestStreamingMode = HttpStreamingMode.NEVER,
                )

            assertInstanceOf(
                ProcessorResult.Success::class.java,
                processor(request, client, evaluator, configuredConnection).process(context(evaluator)),
            )

            val outbound = checkNotNull(client.recordedRequest)
            assertEquals("https://api.example.test/orders/42", outbound.url)
            assertEquals("application/json", outbound.headers["Accept"])
            assertEquals("yes", outbound.headers["X-Expression"])
            assertEquals("operation", outbound.headers["X-Override"])
            assertEquals("order-42", outbound.headers["X-Correlation-ID"])
            assertEquals(
                mapOf("locale" to "en-GB", "expand" to "items", "page" to "2"),
                outbound.queryParams,
            )
            assertEquals(HttpStreamingMode.NEVER, outbound.requestStreamingMode)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodyModes")
    fun `should apply send body mode before reaching the client`(
        caseName: String,
        method: HttpMethod,
        mode: HttpSendBodyMode?,
        expectedBody: String?,
    ) = runTest {
        val evaluator = StubExpressionEvaluator()
        val client = RecordingHttpClient(HttpResponse(statusCode = 204))
        val request = requestConfig(method = method, sendBodyMode = mode)

        processor(request, client, evaluator).process(context(evaluator))

        assertEquals(expectedBody, client.recordedRequest?.body?.decodeToString(), caseName)
    }

    private fun processor(
        requestConfig: HttpRequestConfig,
        client: HttpClient,
        evaluator: MuseExpressionEvaluator,
        connection: HttpRequestConnectionConfig = connectionConfig,
    ): HttpRequestMuseProcessor =
        HttpRequestMuseProcessor(
            component = requestConfig,
            config = connection,
            requestConfig = requestConfig,
            client = client,
            expressionEvaluator = evaluator,
        )

    private fun requestConfig(
        path: String = "/orders",
        url: String? = null,
        method: HttpMethod = HttpMethod.GET,
        headers: Map<String, String> = emptyMap(),
        headersExpression: String? = null,
        queryParams: Map<String, String> = emptyMap(),
        queryParamsExpression: String? = null,
        uriParams: Map<String, String> = emptyMap(),
        uriParamsExpression: String? = null,
        body: String? = null,
        responseTimeoutMs: Long? = null,
        followRedirects: Boolean? = null,
        sendBodyMode: HttpSendBodyMode? = null,
        requestStreamingMode: HttpStreamingMode? = null,
        sendCorrelationId: SendCorrelationId? = null,
        correlationId: String? = null,
        targetConfig: TargetConfig? = null,
    ): HttpRequestConfig =
        HttpRequestConfig(
            docName = "HTTP request contract",
            connectionConfig = ConfigRef.ByReference(connectionConfig),
            path = path,
            url = url,
            method = method,
            headers = headers,
            headersExpression = headersExpression,
            queryParams = queryParams,
            queryParamsExpression = queryParamsExpression,
            uriParams = uriParams,
            uriParamsExpression = uriParamsExpression,
            body = body,
            responseTimeoutMs = responseTimeoutMs,
            followRedirects = followRedirects,
            sendBodyMode = sendBodyMode,
            requestStreamingMode = requestStreamingMode,
            sendCorrelationId = sendCorrelationId,
            correlationId = correlationId,
            targetConfig = targetConfig,
        )

    private fun TestScope.context(
        evaluator: MuseExpressionEvaluator,
        correlationId: String = "contract-correlation",
    ): ExecutionContext =
        ExecutionContext(
            event = MuseEvent(payload = TypedValue.of("event-payload"), correlationId = correlationId),
            errorHandler = null,
            coroutineScope = this,
            expressionEvaluator = evaluator,
        )

    private class StubExpressionEvaluator(
        private val values: Map<String, Any?> = emptyMap(),
    ) : MuseExpressionEvaluator {
        override fun evaluate(
            expression: String,
            event: MuseEvent,
        ): TypedValue<Any>? {
            val value: Any? = if (values.containsKey(expression)) values[expression] else expression
            return TypedValue.of(value)
        }
    }

    private class RecordingHttpClient(
        private val response: HttpResponse? = null,
        private val failure: Exception? = null,
    ) : HttpClient {
        var recordedRequest: HttpOutboundRequest? = null
            private set
        var closed: Boolean = false
            private set

        override suspend fun execute(request: HttpOutboundRequest): HttpResponse {
            recordedRequest = request
            failure?.let { throw it }
            return checkNotNull(response) { "A response or failure must be configured" }
        }

        override fun close() {
            closed = true
        }
    }

    companion object {
        @JvmStatic
        fun transportFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of("socket timeout", SocketTimeoutException("timed out"), HttpErrorType.TIMEOUT),
                Arguments.of("unknown host", UnknownHostException("unknown.test"), HttpErrorType.CONNECTIVITY),
                Arguments.of("connection refused", ConnectException("refused"), HttpErrorType.CONNECTIVITY),
                Arguments.of("TLS failure", SSLException("handshake failed"), HttpErrorType.SECURITY),
                Arguments.of("generic transport failure", IllegalStateException("broken client"), HttpErrorType.CONNECTIVITY),
            )

        @JvmStatic
        fun bodyModes(): Stream<Arguments> =
            Stream.of(
                Arguments.of("AUTO omits GET bodies", HttpMethod.GET, HttpSendBodyMode.AUTO, null),
                Arguments.of("ALWAYS sends GET bodies", HttpMethod.GET, HttpSendBodyMode.ALWAYS, "event-payload"),
                Arguments.of("NEVER omits POST bodies", HttpMethod.POST, HttpSendBodyMode.NEVER, null),
                Arguments.of("config AUTO sends POST bodies", HttpMethod.POST, null, "event-payload"),
            )
    }
}

package dev.museframework.connectors.http.processor

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.model.component.MuseComponent
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.ResponseValidator
import dev.museframework.http.api.config.SendCorrelationId
import dev.museframework.http.api.config.ValidationResult
import dev.museframework.http.api.model.HttpErrorType
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.model.HttpResponseAttributes
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.api.processor.HttpOutboundRequest
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import io.mockk.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class HttpRequestMuseProcessorTest {
    private lateinit var mockClient: HttpClient
    private lateinit var mockExpressionEvaluator: dev.museframework.engine.api.expression.MuseExpressionEvaluator
    private lateinit var mockComponent: MuseComponent
    private lateinit var mockValidator: ResponseValidator
    private lateinit var testCoroutineScope: CoroutineScope

    private val defaultConnectionConfig =
        HttpRequestConnectionConfig(
            docName = "test-connection",
            host = "api.example.com",
            port = 443,
            protocol = HttpProtocol.HTTPS,
            basePath = "/api/v1",
        )

    @BeforeEach
    fun setup() {
        mockClient = mockk(relaxed = true)
        mockExpressionEvaluator = mockk(relaxed = true)
        mockComponent = mockk(relaxed = true)
        mockValidator = mockk()
        testCoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `should execute successful GET request and return Success`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate("/users", any()) } returns TypedValue.of("/users")

            val response = HttpResponse(statusCode = 200, body = """{"users":[]}""".toByteArray())
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(response, any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Success)
            val successResult = result as ProcessorResult.Success
            assertNotNull(successResult.context.event.payload)
            assertArrayEquals(
                """{"users":[]}""".toByteArray(),
                successResult.context.event.payload
                    ?.getOrNull() as ByteArray,
            )
        }

    @Test
    fun `should return Failure with NOT_FOUND for 404 response`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users/999",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users/999")

            val response = HttpResponse(statusCode = 404, body = """{"error":"not found"}""".toByteArray())
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(response, any()) } returns
                ValidationResult.Failure(
                    errorType = HttpErrorType.NOT_FOUND,
                    description = "HTTP 404",
                )

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failureResult = result as ProcessorResult.Failure
            assertEquals(HttpErrorType.NOT_FOUND, failureResult.error.errorType)
        }

    @Test
    fun `should return Failure with CONNECTIVITY for exception`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            coEvery { mockClient.execute(any()) } throws RuntimeException("Connection refused")

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig,
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failureResult = result as ProcessorResult.Failure
            assertEquals(HttpErrorType.CONNECTIVITY, failureResult.error.errorType)
        }

    @Test
    fun `should return Failure with TIMEOUT for SocketTimeoutException`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            coEvery { mockClient.execute(any()) } throws SocketTimeoutException("Read timed out")

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig,
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failureResult = result as ProcessorResult.Failure
            assertEquals(HttpErrorType.TIMEOUT, failureResult.error.errorType)
            assertEquals("HTTP timeout error", failureResult.error.description)
        }

    @Test
    fun `should return Failure with CONNECTIVITY for UnknownHostException`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            coEvery { mockClient.execute(any()) } throws UnknownHostException("api.example.com")

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig,
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failureResult = result as ProcessorResult.Failure
            assertEquals(HttpErrorType.CONNECTIVITY, failureResult.error.errorType)
            assertEquals("HTTP connectivity error", failureResult.error.description)
            assertTrue(failureResult.error.detailedDescription.contains("DNS resolution failed"))
        }

    @Test
    fun `should return Failure with CONNECTIVITY for ConnectException`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            coEvery { mockClient.execute(any()) } throws ConnectException("Connection refused")

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig,
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failureResult = result as ProcessorResult.Failure
            assertEquals(HttpErrorType.CONNECTIVITY, failureResult.error.errorType)
            assertEquals("HTTP connectivity error", failureResult.error.description)
            assertTrue(failureResult.error.detailedDescription.contains("Connection refused"))
        }

    @Test
    fun `should return Failure with SECURITY for SSLException`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            coEvery { mockClient.execute(any()) } throws SSLException("SSL handshake failed")

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig,
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Failure)
            val failureResult = result as ProcessorResult.Failure
            assertEquals(HttpErrorType.SECURITY, failureResult.error.errorType)
            assertEquals("HTTP security error", failureResult.error.description)
        }

    @Test
    fun `should propagate correlation ID with ALWAYS strategy`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                    sendCorrelationId = SendCorrelationId.ALWAYS,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event =
                MuseEvent(
                    payload = TypedValue.of("test"),
                    correlationId = "test-correlation-id",
                )
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertEquals("test-correlation-id", slot.captured.headers["X-Correlation-ID"])
        }

    @Test
    fun `should propagate correlation ID with AUTO strategy when header not present`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                    sendCorrelationId = SendCorrelationId.AUTO,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event =
                MuseEvent(
                    payload = TypedValue.of("test"),
                    correlationId = "auto-correlation-id",
                )
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertEquals("auto-correlation-id", slot.captured.headers["X-Correlation-ID"])
        }

    @Test
    fun `should not overwrite correlation ID with AUTO strategy when header already present`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                    headers = mapOf("X-Correlation-ID" to "existing-correlation-id"),
                    sendCorrelationId = SendCorrelationId.AUTO,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")
            every { mockExpressionEvaluator.evaluate("existing-correlation-id", any()) } returns TypedValue.of("existing-correlation-id")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event =
                MuseEvent(
                    payload = TypedValue.of("test"),
                    correlationId = "event-correlation-id",
                )
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertEquals("existing-correlation-id", slot.captured.headers["X-Correlation-ID"])
        }

    @Test
    fun `should not propagate correlation ID with NEVER strategy`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                    sendCorrelationId = SendCorrelationId.NEVER,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event =
                MuseEvent(
                    payload = TypedValue.of("test"),
                    correlationId = "never-correlation-id",
                )
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertFalse(slot.captured.headers.containsKey("X-Correlation-ID"))
        }

    @Test
    fun `should evaluate path expression`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "#[payload.id]",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate("#[payload.id]", any()) } returns TypedValue.of("/users/123")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of(mapOf("id" to "/users/123")))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertTrue(slot.captured.url.contains("/users/123"))
        }

    @Test
    fun `should build correct URL with protocol, host, port, basePath and path`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertEquals("https://api.example.com:443/api/v1/users", slot.captured.url)
        }

    @Test
    fun `should encode URI parameter as one path segment`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/orders/{id}/items",
                    method = HttpMethod.GET,
                    uriParams = mapOf("id" to "a b/c?d#é%"),
                )
            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } answers { TypedValue.of(firstArg<String>()) }
            coEvery { mockClient.execute(any()) } returns HttpResponse(statusCode = 200)
            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success
            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )
            val event = MuseEvent()
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }
            assertEquals("https://api.example.com:443/api/v1/orders/a%20b%2Fc%3Fd%23%C3%A9%25/items", slot.captured.url)
        }

    @Test
    fun `should override default header regardless of header name case`() =
        runTest {
            val connection = defaultConnectionConfig.copy(defaultHeaders = mapOf("Accept" to "application/json"))
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(connection),
                    path = "/orders",
                    method = HttpMethod.GET,
                    headers = mapOf("accept" to "application/xml"),
                )
            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } answers { TypedValue.of(firstArg<String>()) }
            coEvery { mockClient.execute(any()) } returns HttpResponse(statusCode = 200)
            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success
            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = connection,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )
            val event = MuseEvent()
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }
            assertEquals(mapOf("accept" to "application/xml"), slot.captured.headers.filterKeys { it.equals("accept", ignoreCase = true) })
        }

    @Test
    fun `should use requestConfig body when specified`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.POST,
                    body = """{"name":"John"}""",
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")
            every { mockExpressionEvaluator.evaluate("""{"name":"John"}""", any()) } returns TypedValue.of("""{"name":"John"}""")

            val response = HttpResponse(statusCode = 201)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("ignored"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertNotNull(slot.captured.body)
            assertEquals("""{"name":"John"}""", String(slot.captured.body!!))
            assertNull(slot.captured.contentType)
        }

    @Test
    fun `should use event payload as body when requestConfig body is null`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.POST,
                    body = null,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            val response = HttpResponse(statusCode = 201)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("""{"name":"Jane"}"""))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertNotNull(slot.captured.body)
            assertEquals("""{"name":"Jane"}""", String(slot.captured.body!!))
        }

    @Test
    fun `should evaluate headers with expression evaluator`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                    headers =
                        mapOf(
                            "Authorization" to "#[payload.token]",
                            "Content-Type" to "application/json",
                        ),
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")
            every { mockExpressionEvaluator.evaluate("#[payload.token]", any()) } returns TypedValue.of("Bearer secret-token")
            every { mockExpressionEvaluator.evaluate("application/json", any()) } returns TypedValue.of("application/json")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of(mapOf("token" to "secret-token")))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertEquals("Bearer secret-token", slot.captured.headers["Authorization"])
            assertEquals("application/json", slot.captured.headers["Content-Type"])
            assertNull(slot.captured.contentType)
        }

    @Test
    fun `should evaluate queryParams with expression evaluator`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                    queryParams =
                        mapOf(
                            "filter" to "#[payload.filter]",
                            "limit" to "10",
                        ),
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")
            every { mockExpressionEvaluator.evaluate("#[payload.filter]", any()) } returns TypedValue.of("active")
            every { mockExpressionEvaluator.evaluate("10", any()) } returns TypedValue.of("10")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of(mapOf("filter" to "active")))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertEquals("active", slot.captured.queryParams["filter"])
            assertEquals("10", slot.captured.queryParams["limit"])
        }

    @Test
    fun `should project the target variable when targetConfig is present`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                    targetConfig =
                        TargetConfig
                            .of("next-flow", "#[payload]"),
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Success)
            assertEquals(
                "/users",
                (result as ProcessorResult.Success).context.event.variables["next-flow"]?.value,
                "The response is projected into the caller's target variable",
            )
        }

    @Test
    fun `should include HttpResponseAttributes in result event`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            val response =
                HttpResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = """{"users":[]}""".toByteArray(),
                )
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Success)
            val successResult = result as ProcessorResult.Success
            val attributes =
                successResult.context.event.attributes
                    ?.getOrNull() as? HttpResponseAttributes
            assertNotNull(attributes)
            assertEquals(200, attributes?.statusCode)
            assertEquals(mapOf("Content-Type" to "application/json"), attributes?.headers)
        }

    @Test
    fun `should fallback to raw string when expression syntax is invalid`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "#[invalid.syntax",
                    method = HttpMethod.GET,
                )

            // Expression evaluator returns null for invalid syntax (fallback behavior)
            every { mockExpressionEvaluator.evaluate("#[invalid.syntax", any()) } returns null

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            // Verify the raw string was used (path contains "#[invalid.syntax" as-is)
            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }
            assertTrue(slot.captured.url.contains("#[invalid.syntax"))
        }

    @Test
    fun `should fallback to raw string when expression references missing variable`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "#[payload.missingField]",
                    method = HttpMethod.GET,
                )

            // Expression evaluator returns TypedValue with null for missing variable
            every { mockExpressionEvaluator.evaluate("#[payload.missingField]", any()) } returns TypedValue.of(null)

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of(mapOf("otherField" to "value")))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            // Verify the raw expression string was used as fallback
            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }
            assertTrue(slot.captured.url.contains("#[payload.missingField]"))
        }

    @Test
    fun `should handle null result from expression evaluation`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "#[payload.nullValue]",
                    method = HttpMethod.GET,
                )

            // Expression evaluator returns null (simulating complete evaluation failure)
            every { mockExpressionEvaluator.evaluate("#[payload.nullValue]", any()) } returns null

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of(mapOf("nullValue" to null)))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            // Verify the raw expression string was used as fallback when evaluate returns null
            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }
            assertTrue(slot.captured.url.contains("#[payload.nullValue]"))
        }

    @Test
    fun `should send empty correlation ID as empty string header with ALWAYS strategy`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                    sendCorrelationId = SendCorrelationId.ALWAYS,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event =
                MuseEvent(
                    payload = TypedValue.of("test"),
                    correlationId = "",
                )
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertEquals("", slot.captured.headers["X-Correlation-ID"])
        }

    @Test
    fun `should send empty correlation ID as empty string header with AUTO strategy`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/users",
                    method = HttpMethod.GET,
                    sendCorrelationId = SendCorrelationId.AUTO,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/users")

            val response = HttpResponse(statusCode = 200)
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event =
                MuseEvent(
                    payload = TypedValue.of("test"),
                    correlationId = "",
                )
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            processor.process(context)

            val slot = slot<HttpOutboundRequest>()
            coVerify { mockClient.execute(capture(slot)) }

            assertEquals("", slot.captured.headers["X-Correlation-ID"])
        }

    @Test
    fun `should handle binary response body without encoding issues`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/image.png",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/image.png")

            val binaryData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val response =
                HttpResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "image/png"),
                    body = binaryData,
                )
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Success)
            val successResult = result as ProcessorResult.Success
            assertArrayEquals(
                binaryData,
                successResult.context.event.payload
                    ?.getOrNull() as ByteArray,
            )
        }

    @Test
    fun `should handle empty response body with success status`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "test-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/empty",
                    method = HttpMethod.GET,
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/empty")

            val response =
                HttpResponse(
                    statusCode = 204,
                    headers = mapOf("Content-Type" to "text/plain"),
                    body = null,
                )
            coEvery { mockClient.execute(any()) } returns response

            every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig.copy(responseValidator = mockValidator),
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val event = MuseEvent(payload = TypedValue.of("test"))
            val context =
                ExecutionContext(
                    event = event,
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val result = processor.process(context)

            assertTrue(result is ProcessorResult.Success)
            val successResult = result as ProcessorResult.Success
            assertNull(
                successResult.context.event.payload
                    ?.getOrNull(),
            )
        }

    @Test
    fun `request processor preserves streaming payload at outbound boundary`() = runTest {
        val requestConfig = HttpRequestConfig(
            docName = "test-request",
            connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
            path = "/users",
            method = HttpMethod.POST,
            responseValidator = mockValidator,
        )
        every { mockExpressionEvaluator.evaluate("/users", any()) } returns TypedValue.of("/users")
        every { mockValidator.validate(any(), any()) } returns ValidationResult.Success
        coEvery { mockClient.execute(any()) } returns HttpResponse(statusCode = 200, body = "ok".toByteArray())

        val processor = HttpRequestMuseProcessor(
            component = mockComponent,
            config = defaultConnectionConfig,
            requestConfig = requestConfig,
            client = mockClient,
            expressionEvaluator = mockExpressionEvaluator,
        )
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("stream-body".toByteArray())
        val event = MuseEvent(payload = TypedValue.of(provider as Any, "text/plain"))
        val context = ExecutionContext(
            event = event,
            errorHandler = null,
            coroutineScope = testCoroutineScope,
            expressionEvaluator = mockExpressionEvaluator,
        )

        processor.process(context)

        val slot = slot<HttpOutboundRequest>()
        coVerify { mockClient.execute(capture(slot)) }

        assertNull(slot.captured.body)
        assertNotNull(slot.captured.bodyProvider)
        assertEquals("text/plain", slot.captured.contentType)
        assertEquals("stream-body", slot.captured.bodyProvider!!.openCursor().readBytes().decodeToString())
    }

    @Test
    fun `should pass bodyProvider when payload is streaming provider`() = runTest {
        val requestConfig =
            HttpRequestConfig(
                docName = "test-request",
                connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                path = "/stream",
                method = HttpMethod.POST,
                body = null,
            )

        every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of("/stream")

        val response = HttpResponse(statusCode = 200)
        coEvery { mockClient.execute(any()) } returns response
        every { mockValidator.validate(any(), any()) } returns ValidationResult.Success

        val processor =
            HttpRequestMuseProcessor(
                component = mockComponent,
                config = defaultConnectionConfig,
                requestConfig = requestConfig.copy(responseValidator = mockValidator),
                client = mockClient,
                expressionEvaluator = mockExpressionEvaluator,
            )

        val bodyProvider = RepeatableInMemoryCursorStreamProvider.fromBytes("stream-body".toByteArray())
        val event = MuseEvent(payload = TypedValue.of(bodyProvider as Any))
        val context =
            ExecutionContext(
                event = event,
                errorHandler = null,
                coroutineScope = testCoroutineScope,
                expressionEvaluator = mockExpressionEvaluator,
            )

        processor.process(context)

        val slot = slot<HttpOutboundRequest>()
        coVerify { mockClient.execute(capture(slot)) }

        assertNull(slot.captured.body)
        assertNotNull(slot.captured.bodyProvider)
        assertEquals("stream-body", slot.captured.bodyProvider!!.openCursor().readBytes().decodeToString())
    }

    @Test
    fun `request processor keeps streaming response body as payload provider`() = runTest {
        val requestConfig = HttpRequestConfig(
            docName = "test-request",
            connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
            path = "/users",
            method = HttpMethod.GET,
            responseValidator = mockValidator,
        )
        every { mockExpressionEvaluator.evaluate("/users", any()) } returns TypedValue.of("/users")
        every { mockValidator.validate(any(), any()) } returns ValidationResult.Success
        val responseProvider = RepeatableInMemoryCursorStreamProvider.fromBytes("stream-response".toByteArray())
        coEvery { mockClient.execute(any()) } returns HttpResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "text/plain"),
            bodyProvider = responseProvider,
        )

        val processor = HttpRequestMuseProcessor(
            component = mockComponent,
            config = defaultConnectionConfig,
            requestConfig = requestConfig,
            client = mockClient,
            expressionEvaluator = mockExpressionEvaluator,
        )
        val context = ExecutionContext(
            event = MuseEvent(payload = TypedValue.of("request")),
            errorHandler = null,
            coroutineScope = testCoroutineScope,
            expressionEvaluator = mockExpressionEvaluator,
        )

        val result = processor.process(context) as ProcessorResult.Success

        val payloadProvider = result.context.event.payload!!.asCursorStreamProvider()
        assertNotNull(payloadProvider)
        assertEquals("stream-response", payloadProvider!!.openCursor().readBytes().decodeToString())
    }

    @Test
    fun `request processor preserves a provider returned by an explicit body expression`() = runTest {
        val requestConfig =
            HttpRequestConfig(
                docName = "test-request",
                connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                path = "/users",
                method = HttpMethod.POST,
                body = "#[vars.requestBody]",
                responseValidator = mockValidator,
            )
        val requestProvider = RepeatableInMemoryCursorStreamProvider.fromBytes("expression-body".toByteArray())
        every { mockExpressionEvaluator.evaluate("/users", any()) } returns TypedValue.of("/users")
        every { mockExpressionEvaluator.evaluate("#[vars.requestBody]", any()) } returns TypedValue.of(requestProvider as Any)
        every { mockValidator.validate(any(), any()) } returns ValidationResult.Success
        coEvery { mockClient.execute(any()) } returns HttpResponse(statusCode = 204)
        val processor =
            HttpRequestMuseProcessor(
                component = mockComponent,
                config = defaultConnectionConfig,
                requestConfig = requestConfig,
                client = mockClient,
                expressionEvaluator = mockExpressionEvaluator,
            )
        val context =
            ExecutionContext(
                event = MuseEvent(),
                errorHandler = null,
                coroutineScope = testCoroutineScope,
                expressionEvaluator = mockExpressionEvaluator,
            )

        processor.process(context)

        val requestSlot = slot<HttpOutboundRequest>()
        coVerify { mockClient.execute(capture(requestSlot)) }
        assertNull(requestSlot.captured.body)
        assertSame(requestProvider, requestSlot.captured.bodyProvider)
        assertEquals(TypedValue.MIME_OCTET_STREAM, requestSlot.captured.contentType)
        context.streamingResources.releaseAll()
        assertTrue(requestProvider.isClosed)
    }

    @Test
    fun `request processor registers and bounds a discarded streaming error response`() = runTest {
        val requestConfig =
            HttpRequestConfig(
                docName = "test-request",
                connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                path = "/users",
                method = HttpMethod.GET,
                responseValidator = mockValidator,
            )
        every { mockExpressionEvaluator.evaluate("/users", any()) } returns TypedValue.of("/users")
        every { mockValidator.validate(any(), any()) } returns
            ValidationResult.Failure(HttpErrorType.SERVER_ERROR, "backend failed")
        val responseProvider =
            RepeatableInMemoryCursorStreamProvider.fromBytes("x".repeat(70_000).toByteArray())
        coEvery { mockClient.execute(any()) } returns
            HttpResponse(statusCode = 500, bodyProvider = responseProvider)
        val processor =
            HttpRequestMuseProcessor(
                component = mockComponent,
                config = defaultConnectionConfig,
                requestConfig = requestConfig,
                client = mockClient,
                expressionEvaluator = mockExpressionEvaluator,
            )
        val context =
            ExecutionContext(
                event = MuseEvent(),
                errorHandler = null,
                coroutineScope = testCoroutineScope,
                expressionEvaluator = mockExpressionEvaluator,
            )

        val result = processor.process(context) as ProcessorResult.Failure

        val errorBody = result.error.errorMessage as String
        assertTrue(errorBody.endsWith(" [truncated]"))
        assertEquals(64 * 1024 + " [truncated]".length, errorBody.length)
        assertFalse(responseProvider.isClosed)

        context.streamingResources.releaseAll()
        assertTrue(responseProvider.isClosed)
    }

    @Test
    fun `request processor propagates coroutine cancellation during shutdown`() =
        runTest {
            val requestConfig =
                HttpRequestConfig(
                    docName = "cancelled-request",
                    connectionConfig = ConfigRef.ByReference(defaultConnectionConfig),
                    path = "/cancelled",
                    method = HttpMethod.GET,
                )
            every { mockExpressionEvaluator.evaluate("/cancelled", any()) } returns TypedValue.of("/cancelled")
            coEvery { mockClient.execute(any()) } throws CancellationException("application shutdown")
            val processor =
                HttpRequestMuseProcessor(
                    component = mockComponent,
                    config = defaultConnectionConfig,
                    requestConfig = requestConfig,
                    client = mockClient,
                    expressionEvaluator = mockExpressionEvaluator,
                )
            val context =
                ExecutionContext(
                    event = MuseEvent(),
                    errorHandler = null,
                    coroutineScope = testCoroutineScope,
                    expressionEvaluator = mockExpressionEvaluator,
                )

            val failure =
                try {
                    processor.process(context)
                    fail("Cancellation must be propagated")
                } catch (expected: CancellationException) {
                    expected
                }

            assertEquals("application shutdown", failure.message)
        }
}

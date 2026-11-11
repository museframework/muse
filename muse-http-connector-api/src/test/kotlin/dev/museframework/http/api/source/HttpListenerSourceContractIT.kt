package dev.museframework.http.api.source

import dev.museframework.http.api.config.ConnectionConfigRef
import dev.museframework.http.api.config.ErrorHttpResponseConfig
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpListenerLifecycle
import dev.museframework.http.api.config.HttpResponseConfig
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.config.SuccessHttpResponseConfig
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpRequest
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.server.HttpListenerServerInitializer
import dev.museframework.http.api.server.HttpServer
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.EvaluationRequest
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.source.MuseEventEnvelope
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.test.assertNotNull

class HttpListenerSourceContractIT {
    private lateinit var server: RecordingHttpServer
    private lateinit var initializer: RecordingHttpListenerServerInitializer
    private lateinit var mockExpressionEvaluator: MuseExpressionEvaluator
    private lateinit var channel: Channel<MuseEventEnvelope>

    private val connectionConfig =
        HttpListenerConnectionConfig(
            name = "test_connection",
            docName = "test connection",
            host = "localhost",
            port = 8081,
        )

    @BeforeEach
    fun setup() {
        server = RecordingHttpServer()
        initializer = RecordingHttpListenerServerInitializer(server)
        mockExpressionEvaluator = mockk(relaxed = true)
        every { mockExpressionEvaluator.evaluate(any<EvaluationRequest.PayloadPreservingValue>(), any()) } answers {
            val request = firstArg<EvaluationRequest.PayloadPreservingValue>()
            mockExpressionEvaluator.evaluate(request.expression, secondArg<MuseEvent>())
        }
        channel = Channel(Channel.UNLIMITED)
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `should initialize server on start`() =
        runTest {
            val config = createDefaultConfig()
            val source = createSource(config)

            source.start(channel)

            assertEquals(connectionConfig, initializer.initializedWith)
            assertTrue(server.started)
        }

    @Test
    fun `should register handler with correct path and methods`() =
        runTest {
            val config =
                createDefaultConfig(
                    path = "/api/users",
                    allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST),
                )
            val source = createSource(config)

            source.start(channel)

            assertEquals("/api/users", server.registeredPath)
            assertEquals(listOf(HttpMethod.GET, HttpMethod.POST), server.registeredMethods)
        }

    @Test
    fun `should combine listener base path for registration and removal`() =
        runTest {
            val connection = connectionConfig.copy(basePath = "/service/")
            val config =
                createDefaultConfig(
                    connection = connection,
                    path = "/api/users",
                    allowedMethods = listOf(HttpMethod.GET),
                )
            val source = createSource(config, connection)

            source.start(channel)
            source.stop()

            assertEquals("/service/api/users", server.registeredPath)
            assertEquals("/service/api/users", server.unregisteredPath)
        }

    @Test
    fun `should send event to channel when request is received`() =
        runTest {
            val config = createDefaultConfig()
            val source = createSource(config)

            source.start(channel)

            val request =
                createHttpRequest(
                    method = HttpMethod.POST,
                    path = "/api/users",
                    body = """{"name":"John"}""".toByteArray(),
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

            val responseDeferred =
                async {
                    server.handle(request)
                }

            val envelope = channel.receive()
            assertNotNull(envelope)
            assertEquals(
                """{"name":"John"}""",
                String(
                    envelope.event.payload
                        ?.getOrNull()
                        ?.toBytes() ?: ByteArray(0),
                ),
            )

            envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))

            val response = responseDeferred.await()
            assertNotNull(response)
        }

    @Test
    fun `listener response uses body provider when event payload is streaming`() = runTest {
        val config = createDefaultConfig()
        val source = createSource(config)
        source.start(channel)
        every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

        val responseDeferred = async { server.handle(createHttpRequest()) }
        val envelope = channel.receive()
        val bodyProvider = RepeatableInMemoryCursorStreamProvider.fromBytes("stream-response".toByteArray())

        envelope.replyTo.complete(MuseEvent(payload = TypedValue.of(bodyProvider as Any, "text/plain")))
        val response = responseDeferred.await()

        assertNull(response.body)
        assertNotNull(response.bodyProvider)
        // The reply owner streams the provider while its retained claim is alive and closes it
        // after serialization; nothing else was exported through this reply.
        assertFalse(bodyProvider.isClosed)
        assertEquals("stream-response", response.bodyProvider!!.openCursor().readBytes().decodeToString())
        val lease = assertNotNull(response.bodyLease)
        lease.close()
        assertTrue(bodyProvider.isClosed)
    }

    @Test
    fun `listener adapts a serialized InputStream body to a retained streaming provider`() = runTest {
        val bodyExpression = "#[serializedResponse]"
        val config =
            createDefaultConfig(
                successResponse = SuccessHttpResponseConfig(body = bodyExpression),
            )
        val source = createSource(config)
        source.start(channel)
        every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

        val jsonBytes = """{"message":"Risorsa non trovata"}""".toByteArray(StandardCharsets.UTF_8)
        val input = TrackingInputStream(ByteArrayInputStream(jsonBytes))
        every { mockExpressionEvaluator.evaluate(bodyExpression, any()) } returns
            TypedValue.of(input as Any, TypedValue.MIME_JSON, StandardCharsets.UTF_8)

        val responseDeferred = async { server.handle(createHttpRequest()) }
        val envelope = channel.receive()
        envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        val response = responseDeferred.await()

        assertNull(response.body, "A serialized stream must not be materialized into the response body")
        val provider = assertNotNull(response.bodyProvider, "A serialized stream must become a streaming body provider")
        assertEquals("application/json", response.headers["Content-Type"])
        assertEquals(0, input.reads, "The source must not read the stream before the adapter streams it")

        val lease = assertNotNull(response.bodyLease)
        val actualBytes = provider.openCursor().use { it.readBytes() }
        assertArrayEquals(jsonBytes, actualBytes)
        assertFalse(String(actualBytes, StandardCharsets.UTF_8).contains("ByteArrayInputStream@"))

        lease.close()
        assertTrue(provider.isClosed)
        assertEquals(1, input.closeCount, "The response lease owns exactly one close of the adapted stream")
    }

    @Test
    fun `listener closes a newly adapted stream when response headers evaluation fails`() = runTest {
        supervisorScope {
            val bodyExpression = "#[serializedResponse]"
            val headersExpression = "#[badHeaders]"
            val config =
                createDefaultConfig(
                    successResponse =
                        SuccessHttpResponseConfig(
                            body = bodyExpression,
                            headersExpression = headersExpression,
                        ),
                )
            val source = createSource(config)
            source.start(channel)
            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

            val input = TrackingInputStream(ByteArrayInputStream("serialized".toByteArray()))
            every { mockExpressionEvaluator.evaluate(bodyExpression, any()) } returns
                TypedValue.of(input as Any, TypedValue.MIME_TEXT)
            every { mockExpressionEvaluator.evaluate(headersExpression, any()) } throws
                IllegalStateException("response headers failed")

            val responseDeferred = async { server.handle(createHttpRequest()) }
            val envelope = channel.receive()
            envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("original")))

            val failure = runCatching { responseDeferred.await() }.exceptionOrNull()
            assertInstanceOf(IllegalStateException::class.java, failure)
            assertEquals(0, input.reads, "Failed response construction must not read the stream")
            assertEquals(1, input.closeCount, "A not-yet-exported stream must be released once")
        }
    }

    @Test
    fun `listener preserves already serialized json string bytes without double encoding`() = runTest {
        val bodyExpression = "#[jsonText]"
        val config = createDefaultConfig(successResponse = SuccessHttpResponseConfig(body = bodyExpression))
        val source = createSource(config)
        source.start(channel)
        every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

        val jsonText = """{"message":"kept exactly"}"""
        every { mockExpressionEvaluator.evaluate(bodyExpression, any()) } returns
            TypedValue.of(jsonText, TypedValue.MIME_JSON)

        val responseDeferred = async { server.handle(createHttpRequest()) }
        val envelope = channel.receive()
        envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        val response = responseDeferred.await()

        assertArrayEquals(jsonText.toByteArray(StandardCharsets.UTF_8), response.body)
        assertEquals("application/json", response.headers["Content-Type"])
        assertNull(response.bodyProvider)
    }

    @Test
    fun `listener preserves binary bytes byte for byte`() = runTest {
        val bodyExpression = "#[binary]"
        val config = createDefaultConfig(successResponse = SuccessHttpResponseConfig(body = bodyExpression))
        val source = createSource(config)
        source.start(channel)
        every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

        val binary = byteArrayOf(0, 1, 2, 127, -1)
        every { mockExpressionEvaluator.evaluate(bodyExpression, any()) } returns
            TypedValue.of(binary, TypedValue.MIME_OCTET_STREAM)

        val responseDeferred = async { server.handle(createHttpRequest()) }
        val envelope = channel.receive()
        envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        val response = responseDeferred.await()

        assertArrayEquals(binary, response.body)
        assertEquals(TypedValue.MIME_OCTET_STREAM, response.headers["Content-Type"])
    }

    @Test
    fun `listener encodes text with the charset declared by the typed value`() = runTest {
        val bodyExpression = "#[textResponse]"
        val config = createDefaultConfig(successResponse = SuccessHttpResponseConfig(body = bodyExpression))
        val source = createSource(config)
        source.start(channel)
        every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

        val text = "caffè non trovato"
        val charset = Charset.forName("ISO-8859-1")
        every { mockExpressionEvaluator.evaluate(bodyExpression, any()) } returns
            TypedValue.of(text, TypedValue.MIME_TEXT, charset)

        val responseDeferred = async { server.handle(createHttpRequest()) }
        val envelope = channel.receive()
        envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        val response = responseDeferred.await()

        assertArrayEquals(text.toByteArray(charset), response.body)
        assertEquals("text/plain; charset=ISO-8859-1", response.headers["Content-Type"])
    }

    @Test
    fun `listener honors an explicit content type override without transforming the body`() = runTest {
        val bodyExpression = "#[serializedResponse]"
        val config =
            createDefaultConfig(
                successResponse =
                    SuccessHttpResponseConfig(
                        body = bodyExpression,
                        headers = mapOf("content-type" to "application/vnd.api+json"),
                    ),
            )
        val source = createSource(config)
        source.start(channel)
        every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

        every { mockExpressionEvaluator.evaluate("application/vnd.api+json", any()) } returns
            TypedValue.of("application/vnd.api+json")
        val jsonBytes = """{"message":"override"}""".toByteArray(StandardCharsets.UTF_8)
        every { mockExpressionEvaluator.evaluate(bodyExpression, any()) } returns
            TypedValue.of(jsonBytes, TypedValue.MIME_JSON)

        val responseDeferred = async { server.handle(createHttpRequest()) }
        val envelope = channel.receive()
        envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        val response = responseDeferred.await()

        assertArrayEquals(jsonBytes, response.body)
        assertEquals("application/vnd.api+json", response.headers["content-type"])
        assertNull(response.headers["Content-Type"])
    }

    @Test
    fun `listener rejects an unsupported structured body instead of using toString`() =
        runTest {
            // The handler failure is expected: keep it out of the test scope's failure propagation,
            // exactly like the other response-generation failure test.
            supervisorScope {
                val bodyExpression = "#[structured]"
                val config = createDefaultConfig(successResponse = SuccessHttpResponseConfig(body = bodyExpression))
                val source = createSource(config)
                source.start(channel)
                every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)
                every { mockExpressionEvaluator.evaluate(bodyExpression, any()) } returns
                    TypedValue.of(mapOf("message" to "Resource not found"), TypedValue.MIME_JAVA)

                val responseDeferred = async { server.handle(createHttpRequest()) }
                val envelope = channel.receive()
                envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))

                val failure = runCatching { responseDeferred.await() }.exceptionOrNull()

                assertNotNull(failure, "An unsupported structured body must fail explicitly")
                assertFalse(failure.message.orEmpty().contains("={"))
                assertFalse(failure.message.orEmpty().contains("{message=Resource not found}"))
            }
        }

    @Test
    fun `should use X-Correlation-ID header if present`() =
        runTest {
            val config = createDefaultConfig()
            val source = createSource(config)

            source.start(channel)

            val request =
                createHttpRequest(
                    headers = mapOf("X-Correlation-ID" to "custom-correlation-id"),
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

            async {
                server.handle(request)
            }

            val envelope = channel.receive()
            assertEquals("custom-correlation-id", envelope.event.correlationId)
            envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        }

    @Test
    fun `should use x-correlation-id header if X-Correlation-ID is not present`() =
        runTest {
            val config = createDefaultConfig()
            val source = createSource(config)

            source.start(channel)

            val request =
                createHttpRequest(
                    headers = mapOf("x-correlation-id" to "lowercase-correlation-id"),
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

            async {
                server.handle(request)
            }

            val envelope = channel.receive()
            assertEquals("lowercase-correlation-id", envelope.event.correlationId)
            envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        }

    @Test
    fun `should generate UUID for correlation ID if no header present`() =
        runTest {
            val config = createDefaultConfig()
            val source = createSource(config)

            source.start(channel)

            val request = createHttpRequest()

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

            async {
                server.handle(request)
            }

            val envelope = channel.receive()
            assertNotNull(envelope.event.correlationId)
            assertTrue(envelope.event.correlationId.isNotEmpty())
            envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        }

    @Test
    fun `listener uses body provider as payload when request has streaming body`() = runTest {
        val config = createDefaultConfig()
        val source = createSource(config)
        source.start(channel)
        every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

        val bodyProvider = RepeatableInMemoryCursorStreamProvider.fromBytes("stream-body".toByteArray())
        val request = HttpRequest(
            method = HttpMethod.POST,
            requestPath = "/test",
            headers = mapOf("Content-Type" to "text/plain"),
            bodyProvider = bodyProvider,
        )
        val responseDeferred = async { server.handle(request) }
        val envelope = channel.receive()

        val payloadProvider = envelope.event.payload!!.asCursorStreamProvider()
        assertNotNull(payloadProvider)
        assertEquals("stream-body", payloadProvider.openCursor().readBytes().decodeToString())

        envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        responseDeferred.await()
    }

    @Test
    fun `should handle empty request body`() =
        runTest {
            val config = createDefaultConfig()
            val source = createSource(config)

            source.start(channel)

            val request = createHttpRequest(body = null)

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

            async {
                server.handle(request)
            }

            val envelope = channel.receive()
            assertNotNull(envelope.event.payload)
            assertEquals(
                0,
                envelope.event.payload
                    ?.getOrNull()
                    ?.toBytes()
                    ?.size,
            )
            envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))
        }

    @Test
    fun `should return 200 response for successful processing`() =
        runTest {
            val config = createDefaultConfig()
            val source = createSource(config)

            source.start(channel)

            val request = createHttpRequest()

            every { mockExpressionEvaluator.evaluate("#[vars.httpStatus default 200]", any()) } returns TypedValue.of(200)

            val responseDeferred =
                async {
                    server.handle(request)
                }

            val envelope = channel.receive()
            envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))

            val response = responseDeferred.await()
            assertEquals(200, response.statusCode)
        }

    @Test
    fun `should return 500 response for an unhandled expression error`() =
        runTest {
            val config = createDefaultConfig()
            val source = createSource(config)

            source.start(channel)

            val request = createHttpRequest()

            every { mockExpressionEvaluator.evaluate("#[vars.httpStatus default 500]", any()) } returns TypedValue.of(500)

            val responseDeferred =
                async {
                    server.handle(request)
                }

            val envelope = channel.receive()
            val errorEvent =
                MuseEvent(
                    error =
                        MuseError(
                            description = "Expression evaluation failed",
                            detailedDescription = "Expression error details",
                            errorType = ErrorType.Mule.EXPRESSION,
                            cause = RuntimeException("Broken expression"),
                        ),
                )
            envelope.replyTo.complete(errorEvent)

            val response = responseDeferred.await()
            assertEquals(500, response.statusCode)
            assertEquals("Expression evaluation failed", String(assertNotNull(response.body)))
        }

    @Test
    fun `default error description is UTF8 text independent of original ASCII payload`() =
        runTest {
            val source = createSource(createDefaultConfig())
            source.start(channel)
            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(500)

            val description = "Errore: città 東京"
            val responseDeferred = async { server.handle(createHttpRequest()) }
            val envelope = channel.receive()
            envelope.replyTo.complete(
                MuseEvent(
                    payload = TypedValue.of("original", TypedValue.MIME_TEXT, StandardCharsets.US_ASCII),
                    error =
                        MuseError(
                            description = description,
                            detailedDescription = description,
                            errorType = ErrorType.Mule.EXPRESSION,
                            cause = RuntimeException("Broken expression"),
                        ),
                ),
            )

            val response = responseDeferred.await()
            assertEquals(500, response.statusCode)
            assertArrayEquals(description.toByteArray(StandardCharsets.UTF_8), assertNotNull(response.body))
            assertEquals("text/plain; charset=UTF-8", response.headers["Content-Type"])
        }

    @Test
    fun `default error description honors explicit Content Type without recoding`() =
        runTest {
            val config =
                createDefaultConfig(
                    errorResponse = ErrorHttpResponseConfig(headers = mapOf("content-type" to "application/problem+json")),
                )
            val source = createSource(config)
            source.start(channel)
            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(500)
            every { mockExpressionEvaluator.evaluate("application/problem+json", any()) } returns
                TypedValue.of("application/problem+json")

            val description = "Errore: città 東京"
            val responseDeferred = async { server.handle(createHttpRequest()) }
            val envelope = channel.receive()
            envelope.replyTo.complete(
                MuseEvent(
                    payload = TypedValue.of("original", TypedValue.MIME_TEXT, StandardCharsets.US_ASCII),
                    error =
                        MuseError(
                            description = description,
                            detailedDescription = description,
                            errorType = ErrorType.Mule.EXPRESSION,
                            cause = RuntimeException("Broken expression"),
                        ),
                ),
            )

            val response = responseDeferred.await()
            assertEquals(500, response.statusCode)
            assertArrayEquals(description.toByteArray(StandardCharsets.UTF_8), assertNotNull(response.body))
            assertEquals("application/problem+json", response.headers["content-type"])
            assertFalse(response.headers.containsKey("Content-Type"))
        }

    @Test
    fun `should return 504 Gateway Timeout when timeout is exceeded`() =
        runTest {
            val config = createDefaultConfig(timeoutMs = 100)
            val source = createSource(config)

            source.start(channel)

            val request = createHttpRequest()

            // Mock expression evaluator to return 504 from the timeout event's httpStatus variable
            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } answers {
                val event = secondArg<MuseEvent>()
                val statusValue = event.variables["httpStatus"]
                statusValue ?: TypedValue.of(200)
            }

            val responseDeferred =
                async {
                    server.handle(request)
                }

            val envelope = channel.receive()
            // Don't complete the deferred - let it timeout

            val response = responseDeferred.await()
            assertEquals(504, response.statusCode)
            val responseBody = assertNotNull(response.body)
            assertEquals("Gateway Timeout", String(responseBody))
            assertNull(response.bodyLease, "A timed-out reply exports no provider claim")
            assertTrue(
                envelope.requestCancellation!!.isCompleted,
                "A timeout signals request cancellation to the engine",
            )

            envelope.replyTo.complete(MuseEvent())
        }

    @Test
    fun `listener releases its temporary reply claims when response generation fails`() =
        runTest {
            supervisorScope {
                val source = createSource(createDefaultConfig())
                source.start(channel)
                val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("never-serialized".toByteArray())
                every { mockExpressionEvaluator.evaluate(any<String>(), any()) } throws
                    IllegalStateException("response expression failed")

                val responseDeferred = async { server.handle(createHttpRequest()) }
                val envelope = channel.receive()
                // The engine registers the final event in the open reply registry first.
                envelope.replyResources!!.register(provider)
                envelope.replyTo.complete(MuseEvent(payload = TypedValue.of(provider as Any)))

                val failure = runCatching { responseDeferred.await() }.exceptionOrNull()

                assertInstanceOf(IllegalStateException::class.java, failure)
                assertTrue(
                    provider.isClosed,
                    "A failed response construction releases every temporary reply claim",
                )
            }
        }

    @Test
    fun `listener signals request cancellation when the HTTP exchange is cancelled`() =
        runTest {
            val source = createSource(createDefaultConfig())
            source.start(channel)
            val responseDeferred = async { server.handle(createHttpRequest()) }
            val envelope = channel.receive()

            responseDeferred.cancel()
            responseDeferred.join()

            assertTrue(
                envelope.requestCancellation!!.isCompleted,
                "An aborted exchange signals request cancellation to the engine",
            )
        }

    @Test
    fun `should wait indefinitely when no timeout is configured`() =
        runTest {
            val config = createDefaultConfig(timeoutMs = null)
            val source = createSource(config)

            source.start(channel)

            val request = createHttpRequest()

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

            val responseDeferred =
                async {
                    server.handle(request)
                }

            val envelope = channel.receive()

            kotlinx.coroutines.delay(200)
            assertFalse(responseDeferred.isCompleted, "The response must remain pending without a listener timeout")
            envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("delayed success")))

            val response = responseDeferred.await()
            assertEquals(200, response.statusCode)
        }

    @Test
    fun `should unregister handler on stop`() =
        runTest {
            val config =
                createDefaultConfig(
                    path = "/api/users",
                    allowedMethods = listOf(HttpMethod.GET),
                )
            val source = createSource(config)

            source.start(channel)
            source.stop()

            assertEquals("/api/users", server.unregisteredPath)
            assertEquals(listOf(HttpMethod.GET), server.unregisteredMethods)
        }

    @Test
    fun `should use custom success response configuration`() =
        runTest {
            val config =
                createDefaultConfig(
                    successResponse =
                        SuccessHttpResponseConfig(
                            statusCode = "#[vars.customStatus default 202]",
                            headers = mapOf("X-Override" to "#[vars.override]"),
                            body = """{"status":"custom"}""",
                            reasonPhrase = "#[vars.reason]",
                            headersExpression = "#[vars.responseHeaders]",
                        ),
                    responseStreamingMode = HttpStreamingMode.ALWAYS,
                )
            val source = createSource(config)

            source.start(channel)

            val request = createHttpRequest()

            every { mockExpressionEvaluator.evaluate("#[vars.customStatus default 202]", any()) } returns TypedValue.of(202)
            every { mockExpressionEvaluator.evaluate("#[vars.reason]", any()) } returns TypedValue.of("Accepted")
            every { mockExpressionEvaluator.evaluate("#[vars.responseHeaders]", any()) } returns
                TypedValue.of(mapOf("X-Dynamic" to "dynamic", "x-override" to "expression"))
            every { mockExpressionEvaluator.evaluate("#[vars.override]", any()) } returns TypedValue.of("configured")
            every { mockExpressionEvaluator.evaluate("""{"status":"custom"}""", any()) } returns
                TypedValue.of("""{"status":"custom"}""", "application/json")

            val responseDeferred =
                async {
                    server.handle(request)
                }

            val envelope = channel.receive()
            envelope.replyTo.complete(MuseEvent(payload = TypedValue.of("success")))

            val response = responseDeferred.await()
            assertEquals(202, response.statusCode)
            assertEquals("Accepted", response.reasonPhrase)
            assertEquals(HttpStreamingMode.ALWAYS, response.streamingMode)
            assertEquals("dynamic", response.headers["X-Dynamic"])
            assertEquals("configured", response.headers["X-Override"])
            assertFalse(response.headers.containsKey("x-override"))
            assertEquals("application/json", response.headers["Content-Type"])
        }

    @Test
    fun `should use custom error response configuration`() =
        runTest {
            val config =
                createDefaultConfig(
                    errorResponse =
                        ErrorHttpResponseConfig(
                            statusCode = "#[vars.errorStatus default 400]",
                            headers = mapOf("X-Error" to "true"),
                            body = """{"error":"custom error"}""",
                        ),
                )
            val source = createSource(config)

            source.start(channel)

            val request = createHttpRequest()

            every { mockExpressionEvaluator.evaluate("#[vars.errorStatus default 400]", any()) } returns TypedValue.of(400)
            every { mockExpressionEvaluator.evaluate("""{"error":"custom error"}""", any()) } returns
                TypedValue.of("""{"error":"custom error"}""")
            every { mockExpressionEvaluator.evaluate("true", any()) } returns TypedValue.of("true")

            val responseDeferred =
                async {
                    server.handle(request)
                }

            val envelope = channel.receive()
            val errorEvent =
                MuseEvent(
                    error =
                        MuseError(
                            description = "Error occurred",
                            detailedDescription = "Error details",
                            errorType = ErrorType.Mule.ANY,
                            cause = RuntimeException("Test error"),
                        ),
                )
            envelope.replyTo.complete(errorEvent)

            val response = responseDeferred.await()
            assertEquals(400, response.statusCode)
            assertTrue(response.headers.entries.containsAll(mapOf("X-Error" to "true").entries))
        }

    @Test
    fun `should populate HttpAttributes with request metadata`() =
        runTest {
            val connection = connectionConfig.copy(basePath = "/service")
            val config =
                createDefaultConfig(
                    path = "/api/*",
                    connection = connection,
                )
            val source = createSource(config, connection)

            source.start(channel)

            val request =
                createHttpRequest(
                    method = HttpMethod.POST,
                    path = "/service/api/users/A B",
                    queryParams = mapOf("filter" to "active"),
                    headers = mapOf("Content-Type" to "application/json"),
                    remoteAddress = "192.168.1.1:52100",
                    rawRequestPath = "/service/api/users/A%20B",
                    requestUri = "/service/api/users/A B?filter=active",
                    rawRequestUri = "/service/api/users/A%20B?filter=active",
                    queryString = "filter=active",
                    uriParams = mapOf("id" to "A B"),
                    version = "HTTP/1.1",
                    scheme = "https",
                    localAddress = "127.0.0.1:8443",
                )

            every { mockExpressionEvaluator.evaluate(any<String>(), any()) } returns TypedValue.of(200)

            async {
                server.handle(request)
            }

            val envelope = channel.receive()
            val attributes = envelope.event.attributes?.getOrNull() as? dev.museframework.http.api.model.HttpAttributes

            assertNotNull(attributes)
            assertEquals(HttpMethod.POST, attributes.method)
            assertEquals("/service/api/users/A B", attributes.requestPath)
            assertEquals(mapOf("filter" to "active"), attributes.queryParams)
            assertEquals(mapOf("Content-Type" to "application/json"), attributes.headers)
            assertEquals("/service/api/users/A B?filter=active", attributes.requestUri)
            assertEquals("192.168.1.1:52100", attributes.remoteAddress)
            assertEquals("/service/api/*", attributes.listenerPath)
            assertEquals("/api/users/A B", attributes.relativePath)
            assertEquals("/users/A%20B", attributes.maskedRequestPath)
            assertEquals("/service/api/users/A%20B", attributes.rawRequestPath)
            assertEquals("/service/api/users/A%20B?filter=active", attributes.rawRequestUri)
            assertEquals("filter=active", attributes.queryString)
            assertEquals(mapOf("id" to "A B"), attributes.uriParams)
            assertEquals("HTTP/1.1", attributes.version)
            assertEquals("https", attributes.scheme)
            assertEquals("127.0.0.1:8443", attributes.localAddress)
            assertNull(attributes.clientCertificate)

            envelope.replyTo.complete(MuseEvent())
        }

    private fun createDefaultConfig(
        path: String = "/",
        allowedMethods: List<HttpMethod> = listOf(HttpMethod.ANY),
        timeoutMs: Long? = null,
        successResponse: HttpResponseConfig = SuccessHttpResponseConfig(),
        errorResponse: HttpResponseConfig = ErrorHttpResponseConfig(),
        responseStreamingMode: HttpStreamingMode = HttpStreamingMode.AUTO,
        connection: HttpListenerConnectionConfig = connectionConfig,
    ): HttpListenerConfig =
        HttpListenerConfig(
            docName = "test-listener",
            docId = "test-listener-id",
            connectionConfigRef = ConnectionConfigRef.ByReference(connection),
            path = path,
            allowedMethods = allowedMethods,
            timeoutMs = timeoutMs,
            successResponse = successResponse,
            errorResponse = errorResponse,
            responseStreamingMode = responseStreamingMode,
            lifecycle = HttpListenerLifecycle.EAGER,
        )

    private fun createSource(
        config: HttpListenerConfig,
        connection: HttpListenerConnectionConfig = connectionConfig,
    ): dev.museframework.http.api.source.HttpListenerMuseSource =
        dev.museframework.http.api.source.HttpListenerMuseSource(
            config = config,
            resolvedConnectionConfig = connection,
            expressionEvaluator = mockExpressionEvaluator,
            initializer = initializer,
            docName = config.docName,
            docId = config.docId,
            docDescription = config.docDescription,
            sourceFile = config.sourceFile,
            sourceLine = config.sourceLine,
            sourceClass = config.sourceClass,
        )

    private fun createHttpRequest(
        method: HttpMethod = HttpMethod.GET,
        path: String = "/",
        queryParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        remoteAddress: String = "127.0.0.1",
        rawRequestPath: String = path,
        requestUri: String = path,
        rawRequestUri: String = requestUri,
        queryString: String = "",
        uriParams: Map<String, String> = emptyMap(),
        version: String = "",
        scheme: String = "",
        localAddress: String = "",
    ): HttpRequest =
        HttpRequest(
            method = method,
            requestPath = path,
            queryParams = queryParams,
            headers = headers,
            body = body,
            remoteAddress = remoteAddress,
            rawRequestPath = rawRequestPath,
            requestUri = requestUri,
            rawRequestUri = rawRequestUri,
            queryString = queryString,
            uriParams = uriParams,
            version = version,
            scheme = scheme,
            localAddress = localAddress,
        )

    private fun Any.toBytes(): ByteArray =
        when (this) {
            is ByteArray -> this
            is String -> toByteArray()
            else -> toString().toByteArray()
        }

    private class TrackingInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        var reads: Int = 0
            private set
        var closeCount: Int = 0
            private set

        override fun read(): Int {
            reads++
            return delegate.read()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            reads++
            return delegate.read(buffer, offset, length)
        }

        override fun close() {
            closeCount++
            delegate.close()
        }
    }

    private class RecordingHttpListenerServerInitializer(
        private val server: HttpServer,
    ) : HttpListenerServerInitializer {
        var initializedWith: HttpListenerConnectionConfig? = null
            private set

        override fun initialize(config: HttpListenerConnectionConfig): HttpServer {
            initializedWith = config
            return server
        }
    }

    private class RecordingHttpServer : HttpServer {
        var started: Boolean = false
            private set
        var stopped: Boolean = false
            private set
        var registeredPath: String? = null
            private set
        var registeredMethods: List<HttpMethod>? = null
            private set
        var unregisteredPath: String? = null
            private set
        var unregisteredMethods: List<HttpMethod>? = null
            private set

        private var handler: (suspend (HttpRequest) -> HttpResponse)? = null

        override suspend fun start() {
            started = true
        }

        override suspend fun stop() {
            stopped = true
        }

        override fun register(
            path: String,
            methods: List<HttpMethod>,
            handler: suspend (HttpRequest) -> HttpResponse,
        ) {
            registeredPath = path
            registeredMethods = methods
            this.handler = handler
        }

        override fun unregister(
            path: String,
            methods: List<HttpMethod>,
        ) {
            unregisteredPath = path
            unregisteredMethods = methods
        }

        suspend fun handle(request: HttpRequest): HttpResponse =
            checkNotNull(handler) { "No HTTP handler has been registered" }(request)
    }
}

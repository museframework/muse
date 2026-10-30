package dev.museframework.connectors.http.processor

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.model.HttpErrorType
import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.model.HttpResponseAttributes
import dev.museframework.http.api.processor.HttpClient
import dev.museframework.http.api.processor.HttpOutboundRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpRequestTargetProjectionTest {
    @Test
    fun `unrelated target evaluator defect escapes the HTTP consumer unchanged`() = runTest {
        val defect = IllegalStateException("target defect")
        val fixture = fixture(this, evaluateTarget = { throw defect })
        try {
            val thrown = assertFailsWith<IllegalStateException> {
                fixture.processor.process(fixture.context)
            }
            assertSame(defect, thrown)
            assertEquals(1, fixture.observation.targetCalls)
        } finally {
            fixture.context.streamingResources.releaseAll()
        }
    }

    @Test
    fun `typed target expression failure keeps the complete pre-request event`() = runTest {
        val failure = MuseExpressionException("bad target expression")
        val fixture = fixture(this, evaluateTarget = { throw failure })
        try {
            val result = assertIs<ProcessorResult.Failure>(fixture.processor.process(fixture.context))
            assertEquals(ErrorType.Mule.EXPRESSION, result.error.errorType)
            assertSame(failure, result.error.cause)
            assertEquals(fixture.context.event.copy(error = result.error), result.context.event)
            assertSame(result.error, result.context.event.error)
            assertEquals(1, fixture.observation.targetCalls)
        } finally {
            fixture.context.streamingResources.releaseAll()
        }
    }

    @Test
    fun `target cancellation escapes without a modeled failure`() = runTest {
        val cancellation = CancellationException("target cancelled")
        val fixture = fixture(this, evaluateTarget = { throw cancellation })
        try {
            val thrown = assertFailsWith<CancellationException> {
                fixture.processor.process(fixture.context)
            }
            assertSame(cancellation, thrown)
        } finally {
            fixture.context.streamingResources.releaseAll()
        }
    }

    @Test
    fun `target evaluates the response and projects onto the caller event`() = runTest {
        val ambient = MuseError(
            description = "ambient",
            detailedDescription = "existing caller error",
            errorType = ErrorType.Mule.EXPRESSION,
            cause = IllegalArgumentException("earlier error"),
        )
        val input = inputEvent().copy(error = ambient)
        val projected = TypedValue.of("selected" as Any)
        val fixture = fixture(this, input = input, evaluateTarget = { projected })
        try {
            val result = assertIs<ProcessorResult.Success>(fixture.processor.process(fixture.context))
            val evaluated = checkNotNull(fixture.observation.targetInput)
            assertIs<ByteArray>(evaluated.payload?.getOrNull())
            assertEquals("response", (evaluated.payload!!.getOrNull() as ByteArray).decodeToString())
            assertEquals(200, assertIs<HttpResponseAttributes>(evaluated.attributes?.getOrNull()).statusCode)
            assertEquals(input.copy(variables = input.variables + ("captured" to projected)), result.context.event)
            assertSame(ambient, result.context.event.error)
            assertEquals(1, fixture.observation.targetCalls)
        } finally {
            fixture.context.streamingResources.releaseAll()
        }
    }

    @Test
    fun `no target returns the response without target evaluation`() = runTest {
        val fixture = fixture(this, target = null, evaluateTarget = { error("must not evaluate target") })
        try {
            val result = assertIs<ProcessorResult.Success>(fixture.processor.process(fixture.context))
            assertEquals("response", (result.context.event.payload!!.getOrNull() as ByteArray).decodeToString())
            assertEquals(0, fixture.observation.targetCalls)
            assertEquals(fixture.context.event.variables, result.context.event.variables)
        } finally {
            fixture.context.streamingResources.releaseAll()
        }
    }

    @Test
    fun `response validation failure bypasses target evaluation`() = runTest {
        val fixture = fixture(
            this,
            response = HttpResponse(statusCode = 404, body = "missing".toByteArray()),
            evaluateTarget = { error("must not evaluate target") },
        )
        try {
            val result = assertIs<ProcessorResult.Failure>(fixture.processor.process(fixture.context))
            assertEquals(HttpErrorType.NOT_FOUND, result.error.errorType)
            assertEquals(0, fixture.observation.targetCalls)
            assertEquals(fixture.context.event.copy(error = result.error), result.context.event)
        } finally {
            fixture.context.streamingResources.releaseAll()
        }
    }

    @Test
    fun `target defect leaves the adopted response provider owned until execution cleanup`() = runTest {
        val provider = CountingProvider()
        val incoming = StreamingResourceRegistry.ownedClaim(provider)
        val defect = IllegalStateException("target after response adoption")
        val fixture = fixture(
            this,
            response = HttpResponse(statusCode = 200, bodyProvider = provider, bodyLease = incoming),
            evaluateTarget = { throw defect },
        )
        try {
            assertSame(defect, assertFailsWith<IllegalStateException> {
                fixture.processor.process(fixture.context)
            })
            assertFalse(provider.isClosed)
            assertEquals(0, provider.releases)
            assertEquals("stream", provider.openCursor().use { it.readBytes().decodeToString() })
            fixture.context.streamingResources.releaseAll()
            assertTrue(provider.isClosed, "The processor must already have closed the incoming claim")
            assertEquals(1, provider.closes)
            assertEquals(1, provider.releases)
        } finally {
            fixture.context.streamingResources.releaseAll()
            incoming.close()
        }
        assertTrue(provider.isClosed)
        assertEquals(1, provider.closes)
        assertEquals(1, provider.releases)
    }

    private class Observation {
        var targetCalls = 0
        var targetInput: MuseEvent? = null
    }

    private data class Fixture(
        val processor: HttpRequestMuseProcessor,
        val context: ExecutionContext,
        val observation: Observation,
    )

    private fun fixture(
        scope: CoroutineScope,
        input: MuseEvent = inputEvent(),
        target: TargetConfig? = TargetConfig.of("captured", TARGET_EXPRESSION),
        response: HttpResponse = HttpResponse(statusCode = 200, body = "response".toByteArray()),
        evaluateTarget: (MuseEvent) -> TypedValue<Any>? = { it.payload },
    ): Fixture {
        val observation = Observation()
        val evaluator = object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? =
                when (expression) {
                    TARGET_EXPRESSION -> {
                        observation.targetCalls += 1
                        observation.targetInput = event
                        evaluateTarget(event)
                    }
                    else -> TypedValue.of(expression as Any)
                }
        }
        val connection = HttpRequestConnectionConfig(
            name = "target-contract", docName = "target connection",
            host = "fixture.invalid", port = 80, protocol = HttpProtocol.HTTP, basePath = "/",
        )
        val request = HttpRequestConfig(
            docName = "target request", connectionConfig = ConfigRef.ByReference(connection),
            path = "/probe", targetConfig = target,
        )
        val client = object : HttpClient {
            override suspend fun execute(request: HttpOutboundRequest): HttpResponse = response
            override fun close() = Unit
        }
        val context = ExecutionContext(
            event = input, errorHandler = null, coroutineScope = scope, expressionEvaluator = evaluator,
        )
        return Fixture(
            HttpRequestMuseProcessor(request, connection, request, client, evaluator),
            context,
            observation,
        )
    }

    private fun inputEvent() = MuseEvent(
        payload = TypedValue.of("caller payload" as Any),
        attributes = TypedValue.of("caller attributes" as Any),
        variables = mapOf("existing" to TypedValue.of("kept" as Any)),
        correlationId = "target-contract-correlation",
    )

    private class CountingProvider(
        private val delegate: MuseCursorStreamProvider =
            RepeatableInMemoryCursorStreamProvider.fromBytes("stream".toByteArray()),
    ) : MuseCursorStreamProvider by delegate {
        var closes = 0
        var releases = 0
        override fun close() {
            closes += 1
            delegate.close()
        }
        override fun releaseResources() {
            releases += 1
            delegate.releaseResources()
        }
    }

    private companion object {
        const val TARGET_EXPRESSION = "#[targetProbe]"
    }
}

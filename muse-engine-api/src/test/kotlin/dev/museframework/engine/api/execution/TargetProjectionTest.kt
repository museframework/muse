package dev.museframework.engine.api.execution

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.api.streaming.MuseCursorStream
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class TargetProjectionTest {
    @Test
    fun `target retains entry payload and stores successful result`() {
        val input = MuseEvent(payload = TypedValue.of("entry"))
        val context = context(input)

        val outcome = context.projectTarget(
            PolicyOutcome.Success(input.copy(payload = TypedValue.of("child"))),
            TargetConfig.of("answer"),
            input,
        )

        val success = assertIs<PolicyOutcome.Success>(outcome)
        assertEquals("entry", success.event.payload?.value)
        assertEquals("child", success.event.variables["answer"]?.value)
    }

    @Test
    fun `target expression uses successful result and projects onto the supplied base`() {
        val input = MuseEvent(
            payload = TypedValue.of("entry"),
            attributes = TypedValue.of("entry-attributes"),
            variables = mapOf("before" to TypedValue.of("kept")),
            correlationId = "caller",
        )
        val result = input.copy(
            payload = TypedValue.of("child"),
            attributes = TypedValue.of("child-attributes"),
            variables = mapOf("after" to TypedValue.of("callee")),
            correlationId = "callee",
        )
        var evaluatedEvent: MuseEvent? = null
        val context = context(input) { expression, event ->
            assertEquals("payload ++ '-projected'", expression)
            evaluatedEvent = event
            TypedValue.of("child-projected" as Any)
        }

        val success = assertIs<PolicyOutcome.Success>(
            context.projectTarget(
                PolicyOutcome.Success(result),
                TargetConfig.of("answer", "payload ++ '-projected'"),
                input,
            ),
        )

        assertSame(result, evaluatedEvent)
        assertEquals("entry", success.event.payload?.value)
        assertEquals("entry-attributes", success.event.attributes?.value)
        assertEquals("caller", success.event.correlationId)
        assertEquals(setOf("before", "answer"), success.event.variables.keys)
        assertEquals("child-projected", success.event.variables["answer"]?.value)
    }

    @Test
    fun `target expression may project null instead of the result payload`() {
        val input = MuseEvent(payload = TypedValue.of("entry"))
        val context = context(input) { _, _ -> null }

        val success = assertIs<PolicyOutcome.Success>(
            context.projectTarget(
                PolicyOutcome.Success(input.copy(payload = TypedValue.of("child"))),
                TargetConfig.of("answer", "null"),
                input,
            ),
        )

        assertEquals(true, success.event.variables.containsKey("answer"))
        assertEquals(null, success.event.variables["answer"])
    }

    @Test
    fun `target leaves failure unchanged without evaluating an expression`() {
        val failureError = error("child")
        val failedEvent = MuseEvent(payload = TypedValue.of("failed"), error = failureError)
        val failure = PolicyOutcome.Failure(failureError, failedEvent)
        val context = context(MuseEvent()) { _, _ -> throw AssertionError("must not evaluate") }

        val outcome = context.projectTarget(failure, TargetConfig.of("answer", "bad"))

        assertSame(failure, outcome)
    }

    @Test
    fun `target evaluation failure belongs to the projecting base`() {
        val base = MuseEvent(
            payload = TypedValue.of("entry"),
            attributes = TypedValue.of("entry-attributes"),
            variables = mapOf("before" to TypedValue.of("kept")),
            correlationId = "caller",
        )
        val result = base.copy(payload = TypedValue.of("recovered"), correlationId = "callee")
        val exception = MuseExpressionException("bad target")
        val context = context(base) { _, _ -> throw exception }

        val failure = assertIs<PolicyOutcome.Failure>(
            context.projectTarget(
                PolicyOutcome.Success(result),
                TargetConfig.of("answer", "bad"),
                base,
            ),
        )

        assertEquals(ErrorType.Mule.EXPRESSION, failure.error.errorType)
        assertSame(exception, failure.error.cause)
        assertEquals("entry", failure.event.payload?.value)
        assertEquals("entry-attributes", failure.event.attributes?.value)
        assertEquals("kept", failure.event.variables["before"]?.value)
        assertEquals("caller", failure.event.correlationId)
        assertSame(failure.error, failure.event.error)
    }

    @Test
    fun `target retains result and projected streaming providers`() {
        val resultProvider = TrackingProvider()
        val projectedProvider = TrackingProvider()
        val registry = StreamingResourceRegistry()
        val input = MuseEvent(payload = TypedValue.of("entry"))
        val context = context(input, registry) { _, _ -> TypedValue.of(projectedProvider as Any) }

        val success = assertIs<PolicyOutcome.Success>(
            context.projectTarget(
                PolicyOutcome.Success(input.copy(payload = TypedValue.of(resultProvider as Any))),
                TargetConfig.of("answer", "provider"),
                input,
            ),
        )
        assertSame(projectedProvider, success.event.variables["answer"]?.value)

        registry.releaseAll()
        assertEquals(1, resultProvider.releases)
        assertEquals(1, projectedProvider.releases)
    }

    @Test
    fun `target lets programming defects escape`() {
        val defect = IllegalStateException("defect")
        val input = MuseEvent()
        val context = context(input) { _, _ -> throw defect }

        val thrown = assertFailsWith<IllegalStateException> {
            context.projectTarget(
                PolicyOutcome.Success(input),
                TargetConfig.of("answer", "bad"),
                input,
            )
        }

        assertSame(defect, thrown)
    }

    private fun context(
        event: MuseEvent,
        registry: StreamingResourceRegistry = StreamingResourceRegistry(),
        evaluate: (String, MuseEvent) -> TypedValue<Any>? = { _, _ -> null },
    ): ExecutionContext = ExecutionContext(
        event = event,
        errorHandler = null,
        coroutineScope = CoroutineScope(Dispatchers.Unconfined),
        expressionEvaluator = object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? =
                evaluate(expression, event)
        },
        streamingResources = registry,
    )

    private fun error(name: String): MuseError = MuseError(
        description = name,
        detailedDescription = name,
        errorType = ErrorType("APP", name, ErrorType.Mule.ANY),
        cause = IllegalStateException(name),
    )

    private class TrackingProvider : MuseCursorStreamProvider {
        override var isClosed = false
        var releases = 0

        override fun openCursor(): MuseCursorStream = error("not opened")

        override fun close() {
            isClosed = true
        }

        override fun releaseResources() {
            releases++
        }
    }
}

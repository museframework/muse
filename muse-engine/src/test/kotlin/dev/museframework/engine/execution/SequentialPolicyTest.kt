package dev.museframework.engine.execution

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ChoiceRoute
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.projectTarget
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.api.expression.EvaluationRequest
import dev.museframework.engine.api.expression.ExpressionIteration
import dev.museframework.engine.api.streaming.StreamingCleanupException
import kotlinx.coroutines.CancellationException
import dev.museframework.engine.api.streaming.MuseCursor
import dev.museframework.engine.api.streaming.MuseCursorProvider
import dev.museframework.engine.execution.policy.ChoicePolicy
import dev.museframework.engine.execution.policy.CorrelationPolicy
import dev.museframework.engine.execution.policy.FlowCallPolicy
import dev.museframework.engine.execution.policy.ForEachPolicy
import dev.museframework.engine.execution.policy.RetryPolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class SequentialPolicyTest {
    @Test
    fun `foreach reads only the next owned iterator batch before invoking the body`() = runTest {
        val iterator = TrackingIterator()
        val batches = mutableListOf<Any?>()
        val input = MuseEvent(payload = TypedValue.of("source"))
        val invocation = TestInvocation(input) { _, event ->
            assertEquals(if (batches.isEmpty()) 2 else 3, iterator.read)
            assertSame(input, event.variables["rootMessage"]?.value)
            batches += event.payload?.value
            PolicyOutcome.Success(event)
        }
        val result = ForEachPolicy(forEachConfig(batchSize = 2), TestBody("body"), collectionEvaluator(iterator)).execute(invocation)
        assertIs<PolicyOutcome.Success>(result)
        assertEquals<List<Any?>>(listOf(listOf(1, 2), listOf(3)), batches)
        assertEquals(1, iterator.closes)
    }

    @Test
    fun `foreach closes an owned iterator on body failure without reading the tail`() = runTest {
        val iterator = TrackingIterator()
        val error = policyError("BODY")
        val invocation = TestInvocation(MuseEvent()) { _, event -> PolicyOutcome.Failure(error, event) }
        val result = ForEachPolicy(forEachConfig(), TestBody("body"), collectionEvaluator(iterator)).execute(invocation)
        assertSame(error, assertIs<PolicyOutcome.Failure>(result).error)
        assertEquals(1, iterator.read)
        assertEquals(1, iterator.closes)
    }

    @Test
    fun `deferred iterator failures preserve completed item variables and close the reader`() = runTest {
        val iterator = TrackingIterator(failAt = 1)
        val invocation = TestInvocation(MuseEvent()) { _, event ->
            PolicyOutcome.Success(event.copy(variables = event.variables + ("done" to TypedValue.of(true))))
        }
        val result = ForEachPolicy(forEachConfig(), TestBody("body"), collectionEvaluator(iterator)).execute(invocation)
        val failure = assertIs<PolicyOutcome.Failure>(result)
        assertEquals(ErrorType.Mule.EXPRESSION, failure.error.errorType)
        assertEquals(true, failure.event.variables["done"]?.value)
        assertEquals(1, iterator.closes)
    }

    @Test
    fun `foreach closes its owned iterator when the body is cancelled`() = runTest {
        val iterator = TrackingIterator()
        val invocation = TestInvocation(MuseEvent()) { _, _ -> throw CancellationException("stop") }
        assertFailsWith<CancellationException> {
            ForEachPolicy(forEachConfig(), TestBody("body"), collectionEvaluator(iterator)).execute(invocation)
        }
        assertEquals(1, iterator.closes)
    }

    @Test
    fun `iterator cleanup failure does not mask a body failure`() = runTest {
        val iterator = TrackingIterator(failClose = true)
        val error = policyError("BODY")
        val invocation = TestInvocation(MuseEvent()) { _, event -> PolicyOutcome.Failure(error, event) }
        val result = ForEachPolicy(forEachConfig(), TestBody("body"), collectionEvaluator(iterator)).execute(invocation)
        assertSame(error, assertIs<PolicyOutcome.Failure>(result).error)
        assertEquals("close failed", (error.cause.suppressed.single() as StreamingCleanupException).failures.single().message)
    }

    @Test
    fun `iterator cleanup failure is suppressed on the original cancellation`() = runTest {
        val iterator = TrackingIterator(failClose = true)
        val cancellation = CancellationException("stop")
        val invocation = TestInvocation(MuseEvent()) { _, _ -> throw cancellation }
        val thrown = assertFailsWith<CancellationException> {
            ForEachPolicy(forEachConfig(), TestBody("body"), collectionEvaluator(iterator)).execute(invocation)
        }
        assertSame(cancellation, thrown)
        assertEquals("close failed", (thrown.suppressed.single() as StreamingCleanupException).failures.single().message)
    }

    @Test
    fun `legacy iterator values remain caller owned`() = runTest {
        val iterator = TrackingIterator()
        val invocation = TestInvocation(MuseEvent()) { _, event ->
            assertSame(iterator, event.payload?.value)
            PolicyOutcome.Success(event)
        }
        val policy = ForEachPolicy(forEachConfig(collection = "items"), TestBody("body"), evaluator { _, _ -> TypedValue.of(iterator as Any) })
        assertIs<PolicyOutcome.Success>(policy.execute(invocation))
        assertEquals(0, iterator.closes)
    }

    @Test
    fun `cancelling a real CSV foreach closes its reader without consuming the tail`() = runTest {
        val bytes = buildString {
            appendLine("id,name")
            repeat(10000) { appendLine("$it,name-$it") }
        }.toByteArray()
        var consumed = 0
        var closed = false
        val input = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(): Int = super.read().also { if (it >= 0) consumed++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) consumed += it }
            override fun close() { closed = true; super.close() }
        }
        val event = MuseEvent(payload = TypedValue.of(input, "application/csv; streaming=true; header=true"))
        val baseline = dev.museframework.engine.api.streaming.StreamingResourceRegistry.statistics()
        val cancellation = CancellationException("stop CSV body")
        val invocation = TestInvocation(event) { _, item ->
            assertEquals("0", (item.payload?.value as Map<*, *>)["id"])
            kotlin.test.assertTrue(consumed < bytes.size)
            throw cancellation
        }
        val evaluator = dev.museframework.engine.expression.MuseExpressionEvaluatorImpl(
            dev.museframework.engine.dataweave.MuseDataWeaveExecutor(), java.util.Properties(),
        )
        val thrown = assertFailsWith<CancellationException> {
            ForEachPolicy(forEachConfig(collection = "#[payload]"), TestBody("body"), evaluator).execute(invocation)
        }
        assertSame(cancellation, thrown)
        kotlin.test.assertTrue(closed)
        kotlin.test.assertTrue(consumed < bytes.size)
        assertEquals(baseline, dev.museframework.engine.api.streaming.StreamingResourceRegistry.statistics())
    }

    @Test
    fun `stock foreach preserves item metadata for single items and keeps value lists for batches`() = runTest {
        for (batchSize in listOf(1, 2)) {
            val values = mutableListOf<String>()
            val invocation = TestInvocation(MuseEvent()) { _, item ->
                val payload = kotlin.test.assertNotNull(item.payload)
                assertEquals(if (batchSize == 1) "application/json" else TypedValue.MIME_JAVA, payload.mimeType)
                val batch = if (batchSize == 1) listOf(payload.value) else payload.value as List<*>
                values += batch.map { (it as ByteArray).toString(Charsets.UTF_8) }
                PolicyOutcome.Success(item)
            }
            val evaluator = dev.museframework.engine.expression.MuseExpressionEvaluatorImpl(
                dev.museframework.engine.dataweave.MuseDataWeaveExecutor(), java.util.Properties(),
            )
            val config = forEachConfig("#[%dw 2.0\noutput application/json\n---\n[1,2]]", batchSize)
            assertIs<PolicyOutcome.Success>(ForEachPolicy(config, TestBody("body"), evaluator).execute(invocation))
            assertEquals(listOf("1", "2"), values)
        }
    }

    private class TrackingIterator(private val failAt: Int? = null, private val failClose: Boolean = false) : Iterator<Any?>, AutoCloseable {
        var read = 0
        var closes = 0
        override fun hasNext() = read < 3
        override fun next(): Any {
            if (read == failAt) throw IllegalArgumentException("bad record")
            return ++read
        }
        override fun close() { closes++; if (failClose) throw IllegalStateException("close failed") }
    }

    private fun collectionEvaluator(iterator: Iterator<Any?>) = object : MuseExpressionEvaluator {
        override fun evaluate(request: EvaluationRequest.Iteration, event: MuseEvent) = ExpressionIteration.owned(iterator)
        override fun evaluate(expression: String, event: MuseEvent) = TypedValue.of(listOf(1, 2, 3) as Any)
    }

    @Test
    fun `choice calls only the first matching route`() = runTest {
        val first = TestBody("first")
        val second = TestBody("second")
        val third = TestBody("third")
        val otherwise = TestBody("otherwise")
        val calls = mutableListOf<String>()
        val invocation = TestInvocation(MuseEvent()) { body, event ->
            calls += (body as TestBody).name
            PolicyOutcome.Success(event.copy(payload = TypedValue.of(body.name)))
        }
        val policy = ChoicePolicy(
            choiceConfig(listOf("false", "true", "true")),
            listOf(first, second, third),
            otherwise,
            evaluator { expression, _ -> TypedValue.of((expression == "true") as Any) },
        )

        val success = assertIs<PolicyOutcome.Success>(policy.execute(invocation))

        assertEquals(listOf("second"), calls)
        assertEquals("second", success.event.payload?.value)
    }

    @Test
    fun `choice calls otherwise when no route matches`() = runTest {
        val otherwise = TestBody("otherwise")
        val invocation = TestInvocation(MuseEvent()) { body, event ->
            PolicyOutcome.Success(event.copy(payload = TypedValue.of((body as TestBody).name)))
        }
        val policy = ChoicePolicy(
            choiceConfig(listOf("false")),
            listOf(TestBody("when")),
            otherwise,
            evaluator { _, _ -> TypedValue.of(false as Any) },
        )

        val success = assertIs<PolicyOutcome.Success>(policy.execute(invocation))

        assertEquals("otherwise", success.event.payload?.value)
    }

    @Test
    fun `choice converts only typed evaluation failures`() = runTest {
        val input = MuseEvent(payload = TypedValue.of("entry"))
        val expressionFailure = MuseExpressionException("bad choice")
        val typedPolicy = ChoicePolicy(
            choiceConfig(listOf("bad")),
            listOf(TestBody("when")),
            null,
            evaluator { _, _ -> throw expressionFailure },
        )

        val failure = assertIs<PolicyOutcome.Failure>(typedPolicy.execute(TestInvocation(input)))
        assertEquals(ErrorType.Mule.EXPRESSION, failure.error.errorType)
        assertSame(expressionFailure, failure.error.cause)
        assertEquals("entry", failure.event.payload?.value)

        val defect = IllegalStateException("defect")
        val defectivePolicy = ChoicePolicy(
            choiceConfig(listOf("bad")),
            listOf(TestBody("when")),
            null,
            evaluator { _, _ -> throw defect },
        )
        assertSame(defect, assertFailsWith<IllegalStateException> { defectivePolicy.execute(TestInvocation(input)) })
    }

    @Test
    fun `choice lets typed failures from a selected body escape`() = runTest {
        val bodyFailure = MuseExpressionException("selected body failed")
        val policy = ChoicePolicy(
            choiceConfig(listOf("true")),
            listOf(TestBody("selected")),
            null,
            evaluator { _, _ -> TypedValue.of(true as Any) },
        )
        val invocation = TestInvocation(MuseEvent()) { _, _ -> throw bodyFailure }

        val thrown = assertFailsWith<MuseExpressionException> { policy.execute(invocation) }

        assertSame(bodyFailure, thrown)
    }

    @Test
    fun `choice lets typed failures from the fallback body escape`() = runTest {
        val bodyFailure = MuseExpressionException("fallback body failed")
        val policy = ChoicePolicy(
            choiceConfig(listOf("false")),
            listOf(TestBody("unselected")),
            TestBody("fallback"),
            evaluator { _, _ -> TypedValue.of(false as Any) },
        )
        val invocation = TestInvocation(MuseEvent()) { body, _ ->
            assertEquals("fallback", (body as TestBody).name)
            throw bodyFailure
        }

        val thrown = assertFailsWith<MuseExpressionException> { policy.execute(invocation) }

        assertSame(bodyFailure, thrown)
    }

    @Test
    fun `foreach batches sequentially threads variables and restores entry message`() = runTest {
        val input = MuseEvent(
            payload = TypedValue.of(listOf(1, 2, 3)),
            attributes = TypedValue.of("entry-attributes"),
            variables = mapOf("seed" to TypedValue.of("kept")),
        )
        val seen = mutableListOf<MuseEvent>()
        val invocation = TestInvocation(input) { _, event ->
            seen += event
            PolicyOutcome.Success(
                event.copy(
                    payload = TypedValue.of("body-payload"),
                    attributes = TypedValue.of("body-attributes"),
                    variables = event.variables + ("last" to TypedValue.of(event.payload?.value)),
                ),
            )
        }
        val policy = ForEachPolicy(
            forEachConfig(batchSize = 2),
            TestBody("body"),
            evaluator { _, _ -> null },
        )

        val success = assertIs<PolicyOutcome.Success>(policy.execute(invocation))

        assertEquals(listOf(listOf(1, 2), listOf(3)), seen.map { it.payload?.value })
        assertEquals(listOf(1, 2), seen.map { it.variables["counter"]?.value })
        assertSame(input, seen[0].variables["rootMessage"]?.value)
        assertEquals(listOf(1, 2), seen[1].variables["last"]?.value)
        assertEquals(input.payload, success.event.payload)
        assertEquals(input.attributes, success.event.attributes)
        assertEquals(listOf(3), success.event.variables["last"]?.value)
        assertEquals("kept", success.event.variables["seed"]?.value)
    }

    @Test
    fun `foreach returns the full failing item and skips later items`() = runTest {
        val input = MuseEvent(
            payload = TypedValue.of(listOf(1, 2, 3)),
            attributes = TypedValue.of("entry-attributes"),
        )
        val trace = mutableListOf<Int>()
        val itemError = policyError("ITEM")
        val invocation = TestInvocation(input) { _, event ->
            val item = event.payload?.value as Int
            trace += item
            if (item == 2) {
                val failed = event.copy(
                    payload = TypedValue.of("failed-$item"),
                    attributes = TypedValue.of("failed-attributes"),
                    variables = event.variables + ("mutation" to TypedValue.of("retained")),
                    error = itemError,
                    correlationId = "failed-correlation",
                )
                PolicyOutcome.Failure(itemError, failed)
            } else {
                PolicyOutcome.Success(event.copy(variables = event.variables + ("first" to TypedValue.of("done"))))
            }
        }
        val policy = ForEachPolicy(forEachConfig(), TestBody("body"), evaluator { _, _ -> null })

        val failure = assertIs<PolicyOutcome.Failure>(policy.execute(invocation))

        assertEquals(listOf(1, 2), trace)
        assertEquals("failed-2", failure.event.payload?.value)
        assertEquals("failed-attributes", failure.event.attributes?.value)
        assertEquals("retained", failure.event.variables["mutation"]?.value)
        assertEquals("done", failure.event.variables["first"]?.value)
        assertEquals(2, failure.event.variables["counter"]?.value)
        assertSame(input, failure.event.variables["rootMessage"]?.value)
        assertEquals("failed-correlation", failure.event.correlationId)
        assertSame(itemError, failure.event.error)
    }

    @Test
    fun `foreach supports arrays iterables and null singletons from an expression`() = runTest {
        val cases = listOf(
            arrayOf("a", "b") as Any,
            sequenceOf("a", "b").asIterable() as Any,
            null,
        )

        cases.forEach { collection ->
            val seen = mutableListOf<Any?>()
            val invocation = TestInvocation(MuseEvent(payload = TypedValue.of("ignored"))) { _, event ->
                seen += event.payload?.value
                PolicyOutcome.Success(event)
            }
            val policy = ForEachPolicy(
                forEachConfig(collection = "items"),
                TestBody("body"),
                evaluator { _, _ -> collection?.let { TypedValue.of(it) } },
            )

            assertIs<PolicyOutcome.Success>(policy.execute(invocation))
            val expected: List<Any?> = if (collection == null) listOf(null) else listOf("a", "b")
            assertEquals(expected, seen)
        }
    }

    @Test
    fun `foreach expression failure uses the entry event`() = runTest {
        val input = MuseEvent(payload = TypedValue.of("entry"), attributes = TypedValue.of("attributes"))
        val exception = MuseExpressionException("bad collection")
        val policy = ForEachPolicy(
            forEachConfig(collection = "bad"),
            TestBody("body"),
            evaluator { _, _ -> throw exception },
        )

        val failure = assertIs<PolicyOutcome.Failure>(policy.execute(TestInvocation(input)))

        assertEquals(ErrorType.Mule.EXPRESSION, failure.error.errorType)
        assertSame(exception, failure.error.cause)
        assertEquals("entry", failure.event.payload?.value)
        assertEquals("attributes", failure.event.attributes?.value)
    }

    @Test
    fun `retry starts each attempt from entry and returns the successful result variant`() = runTest {
        val ambient = policyError("E1")
        val input = MuseEvent(payload = TypedValue.of("entry"), error = ambient)
        val attemptInputs = mutableListOf<MuseEvent>()
        var attempts = 0
        val invocation = TestInvocation(input) { _, event ->
            attemptInputs += event
            attempts++
            if (attempts < 3) {
                val failure = policyError("E$attempts")
                PolicyOutcome.Failure(failure, event.copy(payload = TypedValue.of("failed-$attempts"), error = failure))
            } else {
                PolicyOutcome.Success(event.copy(payload = TypedValue.of("success"), error = ambient))
            }
        }

        val success = assertIs<PolicyOutcome.Success>(RetryPolicy(TestBody("body"), 2, 7).execute(invocation))

        assertEquals(3, attempts)
        assertEquals(listOf("entry", "entry", "entry"), attemptInputs.map { it.payload?.value })
        assertEquals(listOf(7L, 7L), invocation.pauses)
        assertEquals("success", success.event.payload?.value)
        assertSame(ambient, success.event.error)
    }

    @Test
    fun `retry exhaustion contains the last error and restores the entry event`() = runTest {
        val ambient = policyError("E1")
        val input = MuseEvent(
            payload = TypedValue.of("entry"),
            attributes = TypedValue.of("entry-attributes"),
            variables = mapOf("seed" to TypedValue.of("kept")),
            error = ambient,
            correlationId = "caller",
        )
        var last: MuseError? = null
        var attempts = 0
        val invocation = TestInvocation(input) { _, event ->
            attempts++
            policyError("attempt-$attempts").let { failure ->
                last = failure
                PolicyOutcome.Failure(
                    failure,
                    event.copy(payload = TypedValue.of("mutated"), variables = emptyMap(), error = failure),
                )
            }
        }

        val failure = assertIs<PolicyOutcome.Failure>(RetryPolicy(TestBody("body"), 2, 0).execute(invocation))

        assertEquals(3, attempts)
        assertEquals(ErrorType.Mule.RETRY_EXHAUSTED, failure.error.errorType)
        assertEquals(listOf(last), failure.error.childErrors)
        assertEquals(last?.description, failure.error.detailedDescription)
        assertEquals("entry", failure.event.payload?.value)
        assertEquals("entry-attributes", failure.event.attributes?.value)
        assertEquals("kept", failure.event.variables["seed"]?.value)
        assertEquals("caller", failure.event.correlationId)
        assertSame(input, failure.event)
    }

    @Test
    fun `correlation scope restores the outer id on success and failure`() = runTest {
        val input = MuseEvent(payload = TypedValue.of("entry"), correlationId = "outer")
        val body = TestBody("body")
        val successInvocation = TestInvocation(input) { _, event ->
            assertEquals("inner", event.correlationId)
            PolicyOutcome.Success(event.copy(payload = TypedValue.of("success")))
        }
        val policy = CorrelationPolicy(
            WithCorrelationIdConfig(correlationId = "new-id"),
            body,
            evaluator { _, _ -> TypedValue.of("inner" as Any) },
        )

        val success = assertIs<PolicyOutcome.Success>(policy.execute(successInvocation))
        assertEquals("outer", success.event.correlationId)
        assertEquals("success", success.event.payload?.value)

        val failureError = policyError("BODY")
        val failureInvocation = TestInvocation(input) { _, event ->
            PolicyOutcome.Failure(failureError, event.copy(payload = TypedValue.of("failed"), error = failureError))
        }
        val failure = assertIs<PolicyOutcome.Failure>(policy.execute(failureInvocation))
        assertEquals("outer", failure.event.correlationId)
        assertEquals("failed", failure.event.payload?.value)
        assertSame(failureError, failure.event.error)
    }

    @Test
    fun `correlation converts only typed evaluation failures`() = runTest {
        val input = MuseEvent(payload = TypedValue.of("entry"), correlationId = "outer")
        val expressionFailure = MuseExpressionException("bad correlation")
        val typedPolicy = CorrelationPolicy(
            WithCorrelationIdConfig(correlationId = "bad"),
            TestBody("body"),
            evaluator { _, _ -> throw expressionFailure },
        )

        val failure = assertIs<PolicyOutcome.Failure>(typedPolicy.execute(TestInvocation(input)))
        assertEquals(ErrorType.Mule.EXPRESSION, failure.error.errorType)
        assertSame(expressionFailure, failure.error.cause)
        assertEquals("outer", failure.event.correlationId)

        val defect = IllegalStateException("defect")
        val defectivePolicy = CorrelationPolicy(
            WithCorrelationIdConfig(correlationId = "bad"),
            TestBody("body"),
            evaluator { _, _ -> throw defect },
        )
        assertSame(defect, assertFailsWith<IllegalStateException> {
            defectivePolicy.execute(TestInvocation(input))
        })
    }

    @Test
    fun `flow call projects a successful callee from the pre-call event`() = runTest {
        val input = MuseEvent(
            payload = TypedValue.of("entry"),
            attributes = TypedValue.of("entry-attributes"),
            variables = mapOf("seed" to TypedValue.of("kept")),
        )
        val context = policyContext(this, input)
        val invocation = TestInvocation(
            input = input,
            performCall = { _, event ->
                PolicyOutcome.Success(
                    event.copy(
                        payload = TypedValue.of("callee"),
                        attributes = TypedValue.of("callee-attributes"),
                        variables = event.variables + ("callee" to TypedValue.of("discarded")),
                    ),
                )
            },
            performProjection = { outcome, target, base -> context.projectTarget(outcome, target, base) },
        )

        val success = assertIs<PolicyOutcome.Success>(
            FlowCallPolicy(TestBody("flow"), TargetConfig.of("answer")).execute(invocation),
        )

        assertEquals("entry", success.event.payload?.value)
        assertEquals("entry-attributes", success.event.attributes?.value)
        assertEquals(setOf("seed", "answer"), success.event.variables.keys)
        assertEquals("callee", success.event.variables["answer"]?.value)
    }

    private class TestBody(val name: String) : BodyHandle

    private class TestInvocation(
        override val input: MuseEvent,
        private val performProjection: (PolicyOutcome, TargetConfig, MuseEvent) -> PolicyOutcome = { _, _, _ ->
            throw AssertionError("projection was not expected")
        },
        private val performCall: suspend (BodyHandle, MuseEvent) -> PolicyOutcome = { _, event ->
            PolicyOutcome.Success(event)
        },
    ) : PolicyInvocation {
        val pauses = mutableListOf<Long>()

        override suspend fun call(body: BodyHandle, event: MuseEvent, timeoutMillis: Long?): PolicyOutcome =
            performCall(body, event)

        override suspend fun pause(millis: Long) {
            pauses += millis
        }

        override suspend fun <T> group(
            maxConcurrency: Int?,
            block: suspend dev.museframework.engine.api.execution.policy.PolicyGroup.() -> T,
        ): T = throw AssertionError("group was not expected")

        override fun project(outcome: PolicyOutcome, target: TargetConfig, base: MuseEvent): PolicyOutcome =
            performProjection(outcome, target, base)

        override fun <T : MuseCursorProvider<out MuseCursor>> manage(provider: T): T = provider

        override suspend fun submit(
            background: dev.museframework.engine.api.execution.policy.BackgroundHandle,
            body: BodyHandle,
            event: MuseEvent,
        ): dev.museframework.engine.api.execution.policy.BackgroundAdmission =
            throw AssertionError("submit was not expected")
    }

    private fun evaluator(block: (String, MuseEvent) -> TypedValue<Any>?): MuseExpressionEvaluator =
        object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? = block(expression, event)
        }

    private fun choiceConfig(expressions: List<String>): ChoiceConfig = ChoiceConfig(
        docName = "choice",
        docId = "choice",
        whenRoutes = expressions.map { ChoiceRoute(it, emptyList()) },
        otherwise = emptyList(),
    )

    private fun forEachConfig(
        collection: String? = null,
        batchSize: Int = 1,
    ): ForEachConfig = ForEachConfig(
        docName = "foreach",
        docId = "foreach",
        collection = collection,
        batchSize = batchSize,
        pipeline = emptyList(),
    )
}

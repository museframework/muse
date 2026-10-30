package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.*
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.execution.policy.parallelForEachPolicyDeclaration
import dev.museframework.engine.execution.policy.scatterGatherPolicyDeclaration
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.execution.policyContext
import dev.museframework.engine.execution.policyError
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ParallelPolicyIT {
    @Test
    fun `S11 item timers start after permits and do not cancel healthy items`() = runTest {
        val visited = mutableListOf<Int>()
        var active = 0
        var peak = 0
        val factory = candidates(mapOf("item" to { context ->
            val item = context.event.payload?.value as Int
            visited += item
            active++
            peak = maxOf(peak, active)
            try {
                delay(if (item == 0) 100 else 8)
                ProcessorResult.Success(context)
            } finally { active-- }
        }))
        val input = MuseEvent(payload = TypedValue.of((0..4).toList()), correlationId = "parent")
        withPreparedPolicy(parallel(timeout = 10, maxConcurrency = 1), factory) { _, processor ->
            val result = assertIs<ProcessorResult.Failure>(processor.process(policyContext(this, input)))
            assertEquals(ErrorType.Mule.COMPOSITE_ROUTING, result.error.errorType)
            val diagnostic = result.error.errorMessage as Map<*, *>
            assertEquals(setOf("0"), (diagnostic["failures"] as Map<*, *>).keys)
            assertEquals(listOf("1", "2", "3", "4"), (diagnostic["results"] as Map<*, *>).keys.toList())
            assertEquals(ErrorType.Mule.TIMEOUT, result.error.childErrors.single().errorType)
            assertEquals(input.payload, result.context.event.payload)
            assertEquals("parent", result.context.event.correlationId)
        }
        assertEquals((0..4).toList(), visited)
        assertEquals(1, peak)
        assertEquals(0, active)
    }

    @Test
    fun `parallel all-success results are ordered and parent variables stay unchanged`() = runTest {
        val input = MuseEvent(
            payload = TypedValue.of(listOf(1, 2, 3)),
            attributes = TypedValue.of("attributes"),
            variables = mapOf("parent" to TypedValue.of("value")),
            error = policyError("AMBIENT"),
        )
        val factory = candidates(mapOf("item" to {
            val item = it.event.payload?.value as Int
            delay((4 - item) * 5L)
            ProcessorResult.Success(it.withEvent(it.event.copy(variables = mapOf("item" to TypedValue.of(item)))))
        }))
        withPreparedPolicy(parallel(), factory) { _, processor ->
            val result = assertIs<ProcessorResult.Success>(processor.process(policyContext(this, input))).context.event
            val events = result.payload?.value as List<*>
            assertEquals(listOf(1, 2, 3), events.map { (it as MuseEvent).payload?.value })
            assertEquals(input.variables, result.variables)
            assertEquals(input.attributes, result.attributes)
            assertSame(input.error, result.error)
        }
    }

    @Test
    fun `empty parallel collection succeeds without calling the body`() = runTest {
        withPreparedPolicy(parallel(), candidates(mapOf("item" to { error("unexpected body") }))) { _, processor ->
            val input = MuseEvent(payload = TypedValue.of(emptyList<Any>()))
            val result = assertIs<ProcessorResult.Success>(processor.process(policyContext(this, input)))
            assertEquals(emptyList<Any>(), result.context.event.payload?.value)
        }
    }

    @Test
    fun `multiple item failures keep input indices independent of completion order and skip target`() = runTest {
        val errors = listOf(policyError("ZERO"), policyError("TWO"))
        val factory = candidates(mapOf("item" to {
            val item = it.event.payload?.value as Int
            delay((3 - item) * 5L)
            if (item == 1) ProcessorResult.Success(it)
            else ProcessorResult.Failure(errors[if (item == 0) 0 else 1], it)
        }))
        val input = MuseEvent(payload = TypedValue.of(listOf(0, 1, 2)), attributes = TypedValue.of("entry"))
        withPreparedPolicy(parallel(target = TargetConfig.of("answer", "bad")), factory) { _, processor ->
            val result = assertIs<ProcessorResult.Failure>(processor.process(policyContext(this, input)))
            val diagnostic = result.error.errorMessage as Map<*, *>
            assertEquals(listOf("0", "2"), (diagnostic["failures"] as Map<*, *>).keys.toList())
            assertEquals(listOf("1"), (diagnostic["results"] as Map<*, *>).keys.toList())
            assertEquals(errors, result.error.childErrors)
            assertEquals(input.copy(error = result.error), result.context.event)
        }
    }

    @Test
    fun `parallel target projects from the pre-iteration event`() = runTest {
        val input = MuseEvent(payload = TypedValue.of(listOf(1, 2)), attributes = TypedValue.of("entry"))
        withPreparedPolicy(parallel(target = TargetConfig.of("answer")), candidates()) { _, processor ->
            val result = assertIs<ProcessorResult.Success>(processor.process(policyContext(this, input))).context.event
            assertEquals(input.payload, result.payload)
            assertEquals(input.attributes, result.attributes)
            assertEquals(listOf(1, 2), (result.variables["answer"]?.value as List<*>).map { (it as MuseEvent).payload?.value })
        }
    }

    @Test
    fun `S12 an owned scatter route timer cancels and joins other routes`() = runTest {
        val loserFinished = CompletableDeferred<Unit>()
        var cancelledAt: Long? = null
        val factory = candidates(mapOf(
            "first" to { delay(100); ProcessorResult.Success(it) },
            "second" to { delay(5); ProcessorResult.Success(it) },
            "third" to {
                try { awaitCancellation() }
                finally {
                    cancelledAt = currentTime
                    withContext(NonCancellable) { delay(10); loserFinished.complete(Unit) }
                }
            },
        ))
        val input = MuseEvent(payload = TypedValue.of("entry"), variables = mapOf("seed" to TypedValue.of(1)))
        val config = scatter(timeout = 20).copy(
            maxConcurrency = 2,
            routes = listOf("first", "second", "third").map { ScatterGatherRoute(listOf(CaptureConfig(it, it))) },
        )
        withPreparedPolicy(config, factory) { _, processor ->
            val result = assertIs<ProcessorResult.Failure>(processor.process(policyContext(this, input)))
            assertEquals(ErrorType.Mule.TIMEOUT, result.error.errorType)
            assertEquals(input.copy(error = result.error), result.context.event)
            assertTrue(loserFinished.isCompleted)
            assertEquals(20L, cancelledAt, "Third route's own timer would expire at 25 ms")
        }
    }

    @Test
    fun `S13 child TIMEOUT is an ordinary indexed failure and healthy scatter route completes`() = runTest {
        val childTimeout = policyError("CHILD").copy(errorType = ErrorType.Mule.TIMEOUT)
        var healthyFinished = false
        val factory = candidates(mapOf(
            "first" to { ProcessorResult.Failure(childTimeout, it) },
            "second" to { delay(30); healthyFinished = true; ProcessorResult.Success(it) },
        ))
        withPreparedPolicy(scatter(timeout = 100), factory) { _, processor ->
            val result = assertIs<ProcessorResult.Failure>(processor.process(policyContext(this)))
            assertEquals(ErrorType.Mule.COMPOSITE_ROUTING, result.error.errorType)
            assertEquals(listOf(childTimeout), result.error.childErrors)
            assertEquals(setOf("1"), ((result.error.errorMessage as Map<*, *>)["results"] as Map<*, *>).keys)
            assertTrue(healthyFinished)
        }
    }

    @Test
    fun `S13 outer timeout remains cancellation and joins scatter routes`() = runTest {
        var finished = 0
        val action: suspend (ExecutionContext) -> ProcessorResult = {
            try { awaitCancellation() } finally { finished++ }
        }
        withPreparedPolicy(scatter(timeout = 100), candidates(mapOf("first" to action, "second" to action))) { _, processor ->
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(10) { processor.process(policyContext(this)) }
            }
            assertEquals(2, finished)
        }
    }

    @Test
    fun `scatter keeps changed-variable merging including null and removed variables with and without target`() = runTest {
        for (target in listOf(null, TargetConfig.of("answer"))) {
            val input = MuseEvent(
                payload = TypedValue.of("entry"), attributes = TypedValue.of("attributes"),
                variables = mapOf("keep" to TypedValue.of("parent"), "removed" to TypedValue.of("parent"), "nulled" to TypedValue.of(1)),
            )
            val factory = candidates(mapOf(
                "first" to {
                    delay(20)
                    ProcessorResult.Success(it.withEvent(it.event.copy(
                        payload = TypedValue.of("first"),
                        variables = (it.event.variables - "removed") + mapOf("shared" to TypedValue.of("a"), "single" to TypedValue.of(1), "nulled" to null),
                    )))
                },
                "second" to {
                    ProcessorResult.Success(it.withEvent(it.event.copy(
                        payload = TypedValue.of("second"),
                        variables = it.event.variables + mapOf("shared" to TypedValue.of("b")),
                    )))
                },
            ))
            withPreparedPolicy(scatter(target = target), factory) { _, processor ->
                val result = assertIs<ProcessorResult.Success>(processor.process(policyContext(this, input))).context.event
                val events = (if (target == null) result.payload else result.variables["answer"])?.value as List<*>
                assertEquals(listOf("first", "second"), events.map { (it as MuseEvent).payload?.value })
                assertEquals(listOf("a", "b"), result.variables["shared"]?.value)
                assertEquals(1, result.variables["single"]?.value)
                assertEquals("parent", result.variables["removed"]?.value)
                // Preserve the existing merge: a null-valued change contributes no route values.
                assertEquals(emptyList<Any>(), result.variables["nulled"]?.value)
                assertEquals(input.attributes, result.attributes)
                if (target != null) assertEquals(input.payload, result.payload)
            }
        }
    }

    @Test
    fun `scatter nested parallel and Try recovery compose without consulting ambient errors`() = runTest {
        val failure = policyError("RECOVERED")
        val ambient = policyError("AMBIENT")
        val recovered = TryConfig(
            "try", "try", pipeline = listOf(CaptureConfig("fail", "fail")),
            errorHandler = ErrorHandler(listOf(ErrorHandlerStrategy.OnErrorContinue(
                ErrorType.Mule.ANY, listOf(CaptureConfig("recover", "recover")),
            ))),
        )
        val nested = parallel().copy(pipeline = listOf(recovered))
        val config = scatter().copy(routes = listOf(ScatterGatherRoute(listOf(nested)), ScatterGatherRoute(emptyList())))
        val factory = candidates(mapOf(
            "fail" to { ProcessorResult.Failure(failure, it) },
            "recover" to { ProcessorResult.Success(it.withEvent(it.event.copy(payload = TypedValue.of("recovered")))) },
        )).also { it.registerPolicyRouter(TryConfig::class, tryPolicyDeclaration) }
        val input = MuseEvent(payload = TypedValue.of(listOf(1, 2)), error = ambient)
        withPreparedPolicy(config, factory) { _, processor ->
            val result = assertIs<ProcessorResult.Success>(processor.process(policyContext(this, input))).context.event
            val routes = result.payload?.value as List<*>
            val items = (routes.first() as MuseEvent).payload?.value as List<*>
            assertEquals(listOf("recovered", "recovered"), items.map { (it as MuseEvent).payload?.value })
            assertSame(ambient, result.error)
        }
    }

    @Test
    fun `scatter composite retains all indexed failures and skips target evaluation`() = runTest {
        val first = policyError("FIRST")
        val second = policyError("SECOND")
        val input = MuseEvent(payload = TypedValue.of("entry"), attributes = TypedValue.of("attributes"))
        withPreparedPolicy(scatter(target = TargetConfig.of("answer", "bad")), candidates(mapOf(
            "first" to { delay(10); ProcessorResult.Failure(first, it.withEvent(it.event.copy(payload = TypedValue.of("failed")))) },
            "second" to { ProcessorResult.Failure(second, it) },
        ))) { _, processor ->
            val result = assertIs<ProcessorResult.Failure>(processor.process(policyContext(this, input)))
            assertEquals(ErrorType.Mule.COMPOSITE_ROUTING, result.error.errorType)
            assertEquals(listOf(first, second), result.error.childErrors)
            assertEquals(listOf("0", "1"), ((result.error.errorMessage as Map<*, *>)["failures"] as Map<*, *>).keys.toList())
            assertEquals(input.copy(error = result.error), result.context.event)
        }
    }

    @Test
    fun `collection evaluation errors normalize but child expression defects escape`() = runTest {
        val defect = MuseExpressionException("typed defect")
        withPreparedPolicy(parallel().copy(collection = "#[unknownFunction()]"), candidates()) { _, processor ->
            val result = assertIs<ProcessorResult.Failure>(processor.process(policyContext(this)))
            assertEquals(ErrorType.Mule.EXPRESSION, result.error.errorType)
        }
        withPreparedPolicy(parallel(), candidates(mapOf("item" to { throw defect }))) { _, processor ->
            val thrown = assertFailsWith<MuseExpressionException> {
                processor.process(policyContext(this, MuseEvent(payload = TypedValue.of(listOf(1)))))
            }
            assertEquals(defect.message, thrown.message)
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === defect })
        }
    }

    @Test
    fun `non-positive timeouts and concurrency are rejected when the declaration is prepared`() {
        listOf(0L, -1L).forEach { invalidTimeout ->
            assertFailsWith<IllegalArgumentException> {
                runTest {
                    withPreparedPolicy(parallel(timeout = invalidTimeout), candidates()) { _, _ -> }
                }
            }
        }
        listOf(0, -1).forEach { invalidConcurrency ->
            assertFailsWith<IllegalArgumentException> {
                runTest {
                    withPreparedPolicy(parallel(maxConcurrency = invalidConcurrency), candidates()) { _, _ -> }
                }
            }
        }
    }

    private fun candidates(actions: Map<String, suspend (ExecutionContext) -> ProcessorResult> = emptyMap()) =
        policyFactory(actions).also {
            it.registerPolicyRouter(ParallelForEachConfig::class, parallelForEachPolicyDeclaration)
            it.registerPolicyRouter(ScatterGatherConfig::class, scatterGatherPolicyDeclaration)
        }

    private fun parallel(timeout: Long? = null, maxConcurrency: Int? = null, target: TargetConfig? = null) =
        ParallelForEachConfig("parallel", "parallel", collection = null, timeout = timeout,
            maxConcurrency = maxConcurrency, targetConfig = target, pipeline = listOf(CaptureConfig("item", "item")))

    private fun scatter(timeout: Long? = null, target: TargetConfig? = null) =
        ScatterGatherConfig("scatter", "scatter", timeout = timeout, targetConfig = target,
            routes = listOf("first", "second").map { ScatterGatherRoute(listOf(CaptureConfig(it, it))) })
}

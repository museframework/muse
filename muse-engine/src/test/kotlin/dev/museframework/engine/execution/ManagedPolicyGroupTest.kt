package dev.museframework.engine.execution

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.policy.*
import dev.museframework.engine.integration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ManagedPolicyGroupTest {
    @Test
    fun `both declared bodies start concurrently and the loser finishes before return`() = runTest {
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loserFinished = CompletableDeferred<Unit>()
        var started = 0
        val config = groupPolicy { invocation, bodies ->
            invocation.group(maxConcurrency = 2) {
                val first = start(bodies[0], invocation.input)
                val second = start(bodies[1], invocation.input)
                bothStarted.await()
                release.complete(Unit)
                val completed = awaitAny(listOf(first, second))
                cancelAndJoin(listOf(first, second).filter { it !== completed.task })
                assertTrue(loserFinished.isCompleted)
                completed.outcome
            }
        }
        val actions = mapOf<String, suspend (ExecutionContext) -> ProcessorResult>(
            "first" to {
                if (++started == 2) bothStarted.complete(Unit)
                release.await()
                ProcessorResult.Success(it.withEvent(it.event.copy(payload = TypedValue.of("winner"))))
            },
            "second" to {
                try {
                    if (++started == 2) bothStarted.complete(Unit)
                    release.await()
                    awaitCancellation()
                } finally { loserFinished.complete(Unit) }
            },
        )
        withPreparedPolicy(config, policyFactory(actions)) { _, processor ->
            val result = assertIs<ProcessorResult.Success>(processor.process(policyContext(this)))
            assertEquals("winner", result.context.event.payload?.value)
            assertTrue(loserFinished.isCompleted)
        }
    }

    @Test
    fun `group return cancels and joins unobserved work even when returning a generic value`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val config = groupPolicy { invocation, bodies ->
            val value: Int = invocation.group {
                start(bodies[0], invocation.input)
                started.await()
                42
            }
            assertTrue(finished.isCompleted)
            PolicyOutcome.Success(invocation.input.copy(payload = TypedValue.of(value)))
        }
        withPreparedPolicy(config, policyFactory(mapOf("first" to {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { delay(10); finished.complete(Unit) } }
        }))) { _, processor ->
            assertEquals(42, assertIs<ProcessorResult.Success>(processor.process(policyContext(this))).context.event.payload?.value)
        }
    }

    @Test
    fun `unawaited child defects and losing finalizer defects cannot be hidden by success`() = runTest {
        for (inFinally in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val defect = IllegalStateException("loser defect")
            val config = groupPolicy { invocation, bodies ->
                invocation.group {
                    start(bodies[1], invocation.input)
                    started.await()
                    if (!inFinally) delay(10)
                    PolicyOutcome.Success(invocation.input)
                }
            }
            withPreparedPolicy(config, policyFactory(mapOf("second" to {
                started.complete(Unit)
                if (!inFinally) throw defect
                try { awaitCancellation() } finally { throw defect }
            }))) { _, processor ->
                val thrown = assertFailsWith<IllegalStateException> { processor.process(policyContext(this)) }
                assertEquals(defect.message, thrown.message)
                assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === defect })
            }
        }
    }

    @Test
    fun `outer cancellation joins children without turning into a task timeout`() = runTest {
        val finished = CompletableDeferred<Unit>()
        val config = groupPolicy { invocation, bodies ->
            invocation.group {
                await(start(bodies[0], invocation.input, timeoutMillis = 1000))
            }
        }
        withPreparedPolicy(config, policyFactory(mapOf("first" to {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { delay(10); finished.complete(Unit) } }
        }))) { _, processor ->
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(20) { processor.process(policyContext(this)) }
            }
            assertTrue(finished.isCompleted)
        }
    }

    @Test
    fun `tokens limits and body ownership are validated and groups expire`() = runTest {
        lateinit var savedGroup: PolicyGroup
        lateinit var savedTask: PolicyTask
        val config = groupPolicy { invocation, bodies ->
            for (limit in listOf(0, -1)) {
                assertFailsWith<IllegalArgumentException> { invocation.group(limit) { Unit } }
            }
            invocation.group {
                savedGroup = this
                assertFailsWith<IllegalArgumentException> { start(object : BodyHandle {}, invocation.input) }
                assertFailsWith<IllegalArgumentException> { start(bodies[0], invocation.input, 0) }
                savedTask = start(bodies[0], invocation.input)
                assertFailsWith<IllegalArgumentException> { awaitAny(emptyList()) }
                assertFailsWith<IllegalArgumentException> { awaitAny(listOf(savedTask, savedTask)) }
                invocation.group {
                    assertFailsWith<IllegalArgumentException> { await(savedTask) }
                    assertFailsWith<IllegalArgumentException> { cancelAndJoin(listOf(savedTask)) }
                }
                await(savedTask)
            }
        }
        withPreparedPolicy(config) { _, processor -> processor.process(policyContext(this)) }
        assertFailsWith<IllegalStateException> { savedGroup.await(savedTask) }
        assertFailsWith<IllegalStateException> { savedGroup.cancelAndJoin(listOf(savedTask)) }
    }

    @Test
    fun `cancelled tokens throw cancellation while modeled failures stay values`() = runTest {
        val error = policyError("VALUE")
        val config = groupPolicy { invocation, bodies ->
            invocation.group {
                val cancelled = start(bodies[0], invocation.input)
                cancelAndJoin(listOf(cancelled))
                assertFailsWith<CancellationException> { await(cancelled) }
                val failed = start(bodies[1], invocation.input)
                assertSame(error, assertIs<PolicyOutcome.Failure>(await(failed)).error)
                PolicyOutcome.Success(invocation.input)
            }
        }
        withPreparedPolicy(config, policyFactory(mapOf("second" to { ProcessorResult.Failure(error, it) }))) { _, processor ->
            assertIs<ProcessorResult.Success>(processor.process(policyContext(this)))
        }
    }

    @Test
    fun `child claims remain live until structured descendants finish cancellation cleanup`() = runTest {
        val descendantStarted = CompletableDeferred<Unit>()
        val descendantFinished = CompletableDeferred<Unit>()
        val provider = PolicyTrackingProvider()
        val config = groupPolicy { invocation, bodies ->
            invocation.group {
                start(bodies[0], invocation.input)
                descendantStarted.await()
                PolicyOutcome.Success(invocation.input)
            }
        }
        withPreparedPolicy(config, policyFactory(mapOf("first" to { context ->
            context.streamingResources.register(provider)
            context.coroutineScope.launch {
                try {
                    descendantStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(20)
                        assertFalse(provider.isClosed, "Child claim must outlive descendant cleanup")
                        descendantFinished.complete(Unit)
                    }
                }
            }
            awaitCancellation()
        }))) { _, processor ->
            assertIs<ProcessorResult.Success>(processor.process(policyContext(this)))
            assertTrue(descendantFinished.isCompleted)
            assertTrue(provider.isClosed)
            assertEquals(1, provider.releases)
        }
    }

    @Test
    fun `releasing a cancelled sibling claim does not close a shared selected provider`() = runTest {
        val started = CompletableDeferred<Unit>()
        val provider = PolicyTrackingProvider()
        val config = groupPolicy { invocation, bodies ->
            invocation.group {
                val loser = start(bodies[1], invocation.input)
                started.await()
                val winner = start(bodies[0], invocation.input)
                val completion = awaitAny(listOf(loser, winner))
                cancelAndJoin(listOf(loser))
                assertFalse(provider.isClosed)
                completion.outcome
            }
        }
        withPreparedPolicy(config, policyFactory(mapOf(
            "first" to { ProcessorResult.Success(it.withEvent(it.event.copy(payload = TypedValue.of(provider)))) },
            "second" to {
                it.streamingResources.register(provider)
                started.complete(Unit)
                awaitCancellation()
            },
        ))) { _, processor ->
            val context = policyContext(this)
            assertIs<ProcessorResult.Success>(processor.process(context))
            assertFalse(provider.isClosed)
            context.streamingResources.releaseAll()
            assertTrue(provider.isClosed)
            assertEquals(1, provider.releases)
        }
    }

    @Test
    fun `completed providers are adopted before child claims are released`() = runTest {
        val provider = PolicyTrackingProvider()
        val discarded = PolicyTrackingProvider()
        val config = groupPolicy { invocation, bodies ->
            invocation.group {
                val outcome = await(start(bodies[0], invocation.input))
                assertFalse(provider.isClosed)
                assertTrue(discarded.isClosed)
                outcome
            }
        }
        withPreparedPolicy(config, policyFactory(mapOf("first" to {
            it.streamingResources.register(discarded)
            ProcessorResult.Success(it.withEvent(it.event.copy(payload = TypedValue.of(provider))))
        }))) { _, processor ->
            val context = policyContext(this)
            val result = assertIs<ProcessorResult.Success>(processor.process(context))
            assertSame(provider, result.context.event.payload?.value)
            assertFalse(provider.isClosed)
            context.streamingResources.releaseAll()
            assertTrue(provider.isClosed)
            assertEquals(1, provider.releases)
            assertEquals(1, discarded.releases)
        }
    }

    private fun groupPolicy(
        execute: suspend (PolicyInvocation, List<BodyHandle>) -> PolicyOutcome,
    ) = ProbePolicyConfig { definition, _ ->
        val bodies = listOf("first", "second").map { definition.inline(listOf(CaptureConfig(it, it))) }
        PolicyFactory { ExecutionPolicy { invocation -> execute(invocation, bodies) } }
    }
}

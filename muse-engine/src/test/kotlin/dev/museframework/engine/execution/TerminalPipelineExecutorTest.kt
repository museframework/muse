package dev.museframework.engine.execution

import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.Pipeline
import dev.museframework.engine.api.execution.PipelineScope
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalPipelineExecutorTest {
    @Test
    fun `pipeline discovers resources once at entry and once per processor result`() = runTest {
        var traversals = 0
        val values = object : AbstractList<Any>() {
            override val size: Int = 1
            override fun get(index: Int): Any {
                traversals++
                return "record"
            }
        }
        val context = policyContext(this, event = MuseEvent(payload = TypedValue.of(values)))

        context.runUnhandled(pipelineOf(
            probe("first") { ProcessorResult.Success(it) },
            probe("second") { ProcessorResult.Success(it) },
        ))

        assertEquals(3, traversals)
    }

    @Test
    fun `provider inserted in an existing container is managed before the next processor`() = runTest {
        val values = mutableListOf<Any>()
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("late".toByteArray())
        val context = policyContext(this, event = MuseEvent(payload = TypedValue.of(values)))

        context.runUnhandled(pipelineOf(
            probe("insert") { values += provider; ProcessorResult.Success(it) },
            probe("observe") { assertTrue(provider.isManaged); ProcessorResult.Success(it) },
        ))
        context.streamingResources.releaseAll()
        assertTrue(provider.isClosed)
    }

    @Test
    fun `empty pipeline registers providers from its entry event`() = runTest {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("entry".toByteArray())
        val context = policyContext(this, event = MuseEvent(payload = TypedValue.of(provider as Any)))

        context.runUnhandled(Pipeline.empty)

        assertTrue(provider.isManaged)
        context.streamingResources.releaseAll()
        assertTrue(provider.isClosed)
    }

    @BeforeEach
    fun clearMdcBeforeTest() {
        MDC.clear()
    }

    @AfterEach
    fun clearMdcAfterTest() {
        MDC.clear()
    }

    @Test
    fun `raw failure skips tail without dispatching a handler`() =
        runTest {
            val markers = mutableListOf<String>()
            val error = policyError("FAIL")
            val failedEvent =
                MuseEvent(
                    payload = TypedValue.of("failed"),
                    variables = mapOf("changed" to TypedValue.of(7)),
                )
            val handler =
                continueHandler(
                    pipelineOf(
                        probe("handler") {
                            markers += "handler"
                            ProcessorResult.Success(it)
                        },
                    ),
                )
            val context = policyContext(this, handler = handler)
            val result =
                context.runUnhandled(
                    pipelineOf(
                        probe("fail") { ProcessorResult.Failure(error, it.withEvent(failedEvent)) },
                        probe("tail") {
                            markers += "tail"
                            ProcessorResult.Success(it)
                        },
                    ),
                )
            val failure = assertIs<ProcessorResult.Failure>(result)
            assertEquals(failedEvent.copy(error = error), failure.context.event)
            assertEquals(emptyList(), markers)
        }

    @Test
    fun `raw failure preserves every mutation on the failed event`() =
        runTest {
            val error = policyError("MUTATED")
            val failedEvent =
                MuseEvent(
                    payload = TypedValue.of("failed-payload"),
                    attributes = TypedValue.of(mapOf("status" to 503)),
                    variables = mapOf("attempt" to TypedValue.of(3)),
                    correlationId = "failed-correlation",
                )
            val context =
                policyContext(
                    this,
                    event = MuseEvent(correlationId = "entry-correlation"),
                )

            val result =
                context.runUnhandled(
                    pipelineOf(
                        probe("fail") {
                            ProcessorResult.Failure(error, it.withEvent(failedEvent))
                        },
                    ),
                )

            val failure = assertIs<ProcessorResult.Failure>(result)
            assertEquals(failedEvent.copy(error = error), failure.context.event)
        }

    @Test
    fun `empty pipeline returns the entry context as terminal success`() =
        runTest {
            val context = policyContext(this, event = MuseEvent(correlationId = "empty"))

            val success = assertIs<ProcessorResult.Success>(context.runUnhandled(Pipeline.empty))

            assertSame(context, success.context)
        }

    @Test
    fun `success restores the ambient entry error while preserving event mutations`() =
        runTest {
            val ambientError = policyError("E1")
            val processorError = policyError("PROCESSOR")
            val context =
                policyContext(
                    this,
                    event = MuseEvent(error = ambientError, correlationId = "ambient"),
                )

            val result =
                context.runUnhandled(
                    pipelineOf(
                        probe("success") {
                            ProcessorResult.Success(
                                it.withEvent(
                                    it.event.copy(
                                        payload = TypedValue.of("changed"),
                                        error = processorError,
                                    ),
                                ),
                            )
                        },
                    ),
                )

            val success = assertIs<ProcessorResult.Success>(result)
            assertEquals("changed", success.context.event.payload?.value)
            assertSame(ambientError, success.context.event.error)
        }

    @Test
    fun `initial and failed result providers register in the caller registry`() =
        runTest {
            val initialProvider =
                RepeatableInMemoryCursorStreamProvider.fromBytes("initial".toByteArray())
            val failedProvider =
                RepeatableInMemoryCursorStreamProvider.fromBytes("failed".toByteArray())
            val error = policyError("STREAM")
            val context =
                policyContext(
                    this,
                    event = MuseEvent(payload = TypedValue.of(initialProvider as Any)),
                )

            val result =
                context.runUnhandled(
                    pipelineOf(
                        probe("fail") {
                            ProcessorResult.Failure(
                                error,
                                it.withEvent(
                                    it.event.copy(payload = TypedValue.of(failedProvider as Any)),
                                ),
                            )
                        },
                    ),
                )

            assertIs<ProcessorResult.Failure>(result)
            context.streamingResources.releaseAll()
            assertTrue(initialProvider.isClosed)
            assertTrue(failedProvider.isClosed)
        }

    @Test
    fun `processor cancellation is not converted to modeled failure`() =
        runTest {
            val cancellation = CancellationException("cancelled")
            val context = policyContext(this)

            val thrown =
                assertFailsWith<CancellationException> {
                    context.runUnhandled(
                        pipelineOf(probe("cancel") { throw cancellation }),
                    )
                }

            assertEquals(cancellation.message, thrown.message)
        }

    @Test
    fun `processor defect is not converted to modeled failure`() =
        runTest {
            val defect = IllegalArgumentException("defect")
            val context = policyContext(this)

            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    context.runUnhandled(
                        pipelineOf(probe("defect") { throw defect }),
                    )
                }

            assertEquals(defect.message, thrown.message)
        }

    @Test
    fun `each invocation sees one component frame and terminal metadata returns to the caller`() =
        runTest {
            val callerFrame = probe("caller") { ProcessorResult.Success(it) }
            val caller = policyContext(this).pushFrame(callerFrame.component, 41)
            val callerScope = caller.currentScope
            val callerCoroutineScope = caller.coroutineScope
            val callerRegistry = caller.streamingResources
            val rogueScope =
                PipelineScope.FlowScope(
                    MuseFlow.Flow(name = "rogue", docId = "rogue"),
                )
            val rogueCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val rogueRegistry = StreamingResourceRegistry()
            val observedFrames = mutableListOf<Pair<Int, Int>>()

            val result =
                caller.runUnhandled(
                    pipelineOf(
                        probe("first") {
                            observedFrames += it.callStack.size to checkNotNull(it.currentFrame).index
                            ProcessorResult.Success(
                                it.copy(
                                    event = it.event.copy(payload = TypedValue.of("first")),
                                    callStack = persistentListOf(),
                                    coroutineScope = rogueCoroutineScope,
                                    streamingResources = rogueRegistry,
                                    currentScope = rogueScope,
                                ),
                            )
                        },
                        probe("second") {
                            observedFrames += it.callStack.size to checkNotNull(it.currentFrame).index
                            ProcessorResult.Success(
                                it.withEvent(it.event.copy(payload = TypedValue.of("second"))),
                            )
                        },
                    ),
                )

            val success = assertIs<ProcessorResult.Success>(result)
            assertEquals(listOf(2 to 0, 2 to 1), observedFrames)
            assertEquals(caller.callStack, success.context.callStack)
            assertSame(callerScope, success.context.currentScope)
            assertSame(callerCoroutineScope, success.context.coroutineScope)
            assertSame(callerRegistry, success.context.streamingResources)
            assertEquals("second", success.context.event.payload?.value)
        }

    @Test
    fun `nested terminal traversal restores each correlation MDC`() =
        runTest {
            MDC.put("correlationId", "caller")
            val inner = policyContext(this, event = MuseEvent(correlationId = "inner"))
            val outer = policyContext(this, event = MuseEvent(correlationId = "outer"))

            outer.runUnhandled(
                pipelineOf(
                    probe("outer") {
                        assertEquals("outer", MDC.get("correlationId"))
                        inner.runUnhandled(
                            pipelineOf(
                                probe("inner") { innerContext ->
                                    assertEquals("inner", MDC.get("correlationId"))
                                    ProcessorResult.Success(innerContext)
                                },
                            ),
                        )
                        assertEquals("outer", MDC.get("correlationId"))
                        ProcessorResult.Success(it)
                    },
                ),
            )

            assertEquals("caller", MDC.get("correlationId"))
        }

    @Test
    fun `terminal traversal restores caller MDC after a processor defect`() =
        runTest {
            MDC.put("correlationId", "caller")
            val context = policyContext(this, event = MuseEvent(correlationId = "terminal"))

            assertFailsWith<IllegalStateException> {
                context.runUnhandled(
                    pipelineOf(
                        probe("defect") {
                            assertEquals("terminal", MDC.get("correlationId"))
                            throw IllegalStateException("boom")
                        },
                    ),
                )
            }

            assertEquals("caller", MDC.get("correlationId"))
        }

}

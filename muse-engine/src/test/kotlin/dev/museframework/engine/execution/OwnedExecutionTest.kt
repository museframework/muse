package dev.museframework.engine.execution

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.Pipeline
import dev.museframework.engine.api.execution.PipelineScope
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.ResolvedErrorHandler
import dev.museframework.engine.api.execution.ResolvedErrorHandlerStrategy
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class OwnedExecutionTest {
    @Test
    fun `local continue finishes only its owner and restores E1`() =
        runTest {
            val markers = mutableListOf<String>()
            val e1 = policyError("OUTER")
            val e2 = policyError("INNER")
            val context = policyContext(this, MuseEvent(error = e1))
            val owned =
                OwnedExecution(
                    BoundExecution { entry ->
                        entry.runUnhandled(
                            pipelineOf(
                                probe("fail") { ProcessorResult.Failure(e2, it.withError(e2)) },
                                probe("failed-tail") {
                                    markers += "failed-tail"
                                    ProcessorResult.Success(it)
                                },
                            ),
                        )
                    },
                    continueHandler(
                        pipelineOf(
                            probe("recover") {
                                markers += "recover"
                                ProcessorResult.Success(
                                    it.withEvent(
                                        it.event.copy(
                                            variables =
                                                it.event.variables +
                                                    ("recovered" to TypedValue.of(true)),
                                        ),
                                    ),
                                )
                            },
                        ),
                    ),
                )

            val result = assertIs<ProcessorResult.Success>(owned.invoke(context))

            assertSame(e1, result.context.event.error)
            assertEquals(true, result.context.event.variables["recovered"]?.value)
            assertEquals(listOf("recover"), markers)
        }

    @Test
    fun `no matching strategy returns the complete failed event`() =
        runTest {
            val error = policyError("UNMATCHED")
            var parentHandlerEntries = 0
            val failedEvent =
                MuseEvent(
                    payload = TypedValue.of("failed"),
                    attributes = TypedValue.of(mapOf("status" to 503)),
                    variables = mapOf("attempt" to TypedValue.of(2)),
                    correlationId = "failed-correlation",
                )
            val handler =
                errorHandler(
                    errorType = ErrorType.Mule.CRITICAL,
                    pipeline = pipelineOf(probe("must-not-run") { error("unexpected handler") }),
                )
            val owned =
                OwnedExecution(
                    BoundExecution { ProcessorResult.Failure(error, it.withEvent(failedEvent)) },
                    handler,
                )
            val parent =
                policyContext(
                    this,
                    handler =
                        continueHandler(
                            pipelineOf(
                                probe("parent-handler") {
                                    parentHandlerEntries += 1
                                    ProcessorResult.Success(it)
                                },
                            ),
                        ),
                )
            val context = policyContext(this).copy(parent = parent)

            val failure = assertIs<ProcessorResult.Failure>(owned.invoke(context))

            assertSame(error, failure.error)
            assertEquals(failedEvent.copy(error = error), failure.context.event)
            assertEquals(0, parentHandlerEntries)
        }

    @Test
    fun `propagate retains the original error and every handler mutation while restoring caller metadata`() =
        runTest {
            val original = policyError("ORIGINAL")
            val callerFrame = probe("caller") { ProcessorResult.Success(it) }
            val caller = policyContext(this).pushFrame(callerFrame.component, 17)
            val callerScope = caller.currentScope
            val callerCoroutineScope = caller.coroutineScope
            val callerRegistry = caller.streamingResources
            val rogueScope =
                PipelineScope.FlowScope(
                    MuseFlow.Flow(name = "rogue", docId = "rogue"),
                )
            val rogueCoroutineScope = CoroutineScope(coroutineContext)
            val rogueRegistry = StreamingResourceRegistry()
            val handler =
                errorHandler(
                    kind = ResolvedErrorHandlerStrategy.Kind.PROPAGATE,
                    pipeline =
                        pipelineOf(
                            probe("mutate") {
                                assertEquals(caller.callStack, it.callStack.removeAt(it.callStack.lastIndex))
                                assertSame(callerScope, it.currentScope)
                                assertSame(callerCoroutineScope, it.coroutineScope)
                                assertSame(callerRegistry, it.streamingResources)
                                ProcessorResult.Success(
                                    it.copy(
                                        event =
                                            it.event.copy(
                                                payload = TypedValue.of("handler-payload"),
                                                attributes = TypedValue.of(mapOf("status" to 202)),
                                                variables = mapOf("handled" to TypedValue.of(true)),
                                                correlationId = "handler-correlation",
                                            ),
                                        callStack = persistentListOf(),
                                        coroutineScope = rogueCoroutineScope,
                                        streamingResources = rogueRegistry,
                                        currentScope = rogueScope,
                                    ),
                                )
                            },
                        ),
                )
            val owned =
                OwnedExecution(
                    BoundExecution {
                        ProcessorResult.Failure(
                            original,
                            it.copy(
                                event = it.event.copy(payload = TypedValue.of("body-payload")),
                                callStack = persistentListOf(),
                                coroutineScope = rogueCoroutineScope,
                                streamingResources = rogueRegistry,
                                currentScope = rogueScope,
                            ),
                        )
                    },
                    handler,
                )

            val failure = assertIs<ProcessorResult.Failure>(owned.invoke(caller))

            assertSame(original, failure.error)
            assertSame(original, failure.context.event.error)
            assertEquals("handler-payload", failure.context.event.payload?.value)
            assertEquals(mapOf("status" to 202), failure.context.event.attributes?.value)
            assertEquals(true, failure.context.event.variables["handled"]?.value)
            assertEquals("handler-correlation", failure.context.event.correlationId)
            assertEquals(caller.callStack, failure.context.callStack)
            assertSame(callerScope, failure.context.currentScope)
            assertSame(callerCoroutineScope, failure.context.coroutineScope)
            assertSame(callerRegistry, failure.context.streamingResources)
        }

    @Test
    fun `strategy failure leaves the active owner with E3 and skips its tail`() =
        runTest {
            val e2 = policyError("BODY")
            val e3 = policyError("STRATEGY")
            var handlerEntries = 0
            val markers = mutableListOf<String>()
            val handler =
                continueHandler(
                    pipelineOf(
                        probe("handler-fail") {
                            handlerEntries += 1
                            ProcessorResult.Failure(
                                e3,
                                it.withEvent(
                                    it.event.copy(
                                        payload = TypedValue.of("strategy-failed"),
                                        variables = mapOf("handler" to TypedValue.of("mutated")),
                                    ),
                                ),
                            )
                        },
                        probe("handler-tail") {
                            markers += "handler-tail"
                            ProcessorResult.Success(it)
                        },
                    ),
                )
            val owned =
                OwnedExecution(
                    BoundExecution { ProcessorResult.Failure(e2, it.withError(e2)) },
                    handler,
                )

            val failure = assertIs<ProcessorResult.Failure>(owned.invoke(policyContext(this)))

            assertSame(e3, failure.error)
            assertSame(e3, failure.context.event.error)
            assertEquals("strategy-failed", failure.context.event.payload?.value)
            assertEquals("mutated", failure.context.event.variables["handler"]?.value)
            assertEquals(1, handlerEntries)
            assertEquals(emptyList(), markers)
        }

    @Test
    fun `predicate failure becomes EXPRESSION with the original error as child diagnostics`() =
        runTest {
            val original = policyError("ORIGINAL")
            val predicateFailure = MuseExpressionException("predicate exploded")
            var handlerEntries = 0
            val evaluator =
                object : MuseExpressionEvaluator {
                    override fun evaluate(
                        expression: String,
                        event: MuseEvent,
                    ): TypedValue<Any>? = throw predicateFailure
                }
            val handler =
                errorHandler(
                    whenExpression = "#[broken]",
                    pipeline =
                        pipelineOf(
                            probe("must-not-run") {
                                handlerEntries += 1
                                ProcessorResult.Success(it)
                            },
                        ),
                )
            val failedEvent =
                MuseEvent(
                    payload = TypedValue.of("failed"),
                    variables = mapOf("body" to TypedValue.of("mutated")),
                )
            val context = policyContext(this).copy(expressionEvaluator = evaluator)
            val owned =
                OwnedExecution(
                    BoundExecution { ProcessorResult.Failure(original, it.withEvent(failedEvent)) },
                    handler,
                )

            val failure = assertIs<ProcessorResult.Failure>(owned.invoke(context))

            assertEquals(ErrorType.Mule.EXPRESSION, failure.error.errorType)
            assertSame(predicateFailure, failure.error.cause)
            assertEquals(listOf(original), failure.error.childErrors)
            assertEquals("failed", failure.context.event.payload?.value)
            assertEquals("mutated", failure.context.event.variables["body"]?.value)
            assertSame(failure.error, failure.context.event.error)
            assertEquals(0, handlerEntries)
        }

    @Test
    fun `predicate programming defects escape the owner`() =
        runTest {
            val original = policyError("ORIGINAL")
            val defect = IllegalStateException("predicate defect")
            val evaluator =
                object : MuseExpressionEvaluator {
                    override fun evaluate(
                        expression: String,
                        event: MuseEvent,
                    ): TypedValue<Any>? = throw defect
                }
            val context = policyContext(this).copy(expressionEvaluator = evaluator)
            val owned =
                OwnedExecution(
                    BoundExecution { ProcessorResult.Failure(original, it.withError(original)) },
                    errorHandler(
                        whenExpression = "#[defect]",
                        pipeline = Pipeline.empty,
                    ),
                )

            val thrown = assertFailsWith<IllegalStateException> { owned.invoke(context) }

            assertSame(defect, thrown)
        }

    @Test
    fun `predicate matches only boolean true and string true`() =
        runTest {
            val cases =
                listOf(
                    true as Any to true,
                    "true" as Any to true,
                    false as Any to false,
                    "TRUE" as Any to false,
                    1 as Any to false,
                )

            cases.forEachIndexed { index, (predicateValue, shouldRecover) ->
                val error = policyError("PREDICATE-$index")
                val evaluator =
                    object : MuseExpressionEvaluator {
                        override fun evaluate(
                            expression: String,
                            event: MuseEvent,
                        ): TypedValue<Any> = TypedValue.of(predicateValue)
                    }
                val context = policyContext(this).copy(expressionEvaluator = evaluator)
                val owned =
                    OwnedExecution(
                        BoundExecution { ProcessorResult.Failure(error, it.withError(error)) },
                        errorHandler(
                            whenExpression = "#[predicate]",
                            pipeline = pipelineOf(probe("recover-$index") { ProcessorResult.Success(it) }),
                        ),
                    )

                val result = owned.invoke(context)

                if (shouldRecover) {
                    val success = assertIs<ProcessorResult.Success>(result)
                    assertNull(success.context.event.error)
                } else {
                    val failure = assertIs<ProcessorResult.Failure>(result)
                    assertSame(error, failure.error)
                }
            }
        }

    @Test
    fun `equal handler definitions remain reusable by distinct owner invocations`() =
        runTest {
            var recoveries = 0
            val sharedHandler =
                continueHandler(
                    pipelineOf(
                        probe("recover") {
                            recoveries += 1
                            ProcessorResult.Success(it)
                        },
                    ),
                )
            val error = policyError("REUSED")
            val owned =
                OwnedExecution(
                    BoundExecution { ProcessorResult.Failure(error, it.withError(error)) },
                    sharedHandler,
                )

            assertIs<ProcessorResult.Success>(owned.invoke(policyContext(this)))
            assertIs<ProcessorResult.Success>(owned.invoke(policyContext(this)))
            assertEquals(2, recoveries)
        }

    @Test
    fun `nested local recovery restores E1 before the outer handler continues`() =
        runTest {
            val e1 = policyError("OUTER")
            val e2 = policyError("INNER")
            val observedErrors = mutableListOf<Any?>()
            val inner =
                OwnedExecution(
                    BoundExecution { ProcessorResult.Failure(e2, it.withError(e2)) },
                    continueHandler(
                        pipelineOf(
                            probe("recover-inner") {
                                ProcessorResult.Success(
                                    it.withEvent(
                                        it.event.copy(
                                            variables = it.event.variables + ("inner" to TypedValue.of(true)),
                                        ),
                                    ),
                                )
                            },
                        ),
                    ),
                )
            val outer =
                OwnedExecution(
                    BoundExecution { ProcessorResult.Failure(e1, it.withError(e1)) },
                    continueHandler(
                        pipelineOf(
                            probe("invoke-inner") { inner.invoke(it) },
                            probe("after-inner") {
                                observedErrors += it.event.error
                                ProcessorResult.Success(
                                    it.withEvent(
                                        it.event.copy(
                                            variables = it.event.variables + ("outer" to TypedValue.of(true)),
                                        ),
                                    ),
                                )
                            },
                        ),
                    ),
                )

            val success = assertIs<ProcessorResult.Success>(outer.invoke(policyContext(this)))

            assertEquals(1, observedErrors.size)
            assertSame(e1, observedErrors.single())
            assertNull(success.context.event.error)
            assertEquals(true, success.context.event.variables["inner"]?.value)
            assertEquals(true, success.context.event.variables["outer"]?.value)
        }

    @Test
    fun `successful body restores the ambient error even from a stale returned context`() =
        runTest {
            val ambient = policyError("AMBIENT")
            val stale = policyError("STALE")
            val context = policyContext(this, MuseEvent(error = ambient))
            val owned =
                OwnedExecution(
                    BoundExecution {
                        ProcessorResult.Success(
                            it.withEvent(
                                it.event.copy(
                                    payload = TypedValue.of("changed"),
                                    error = stale,
                                ),
                            ),
                        )
                    },
                    handler = null,
                )

            val success = assertIs<ProcessorResult.Success>(owned.invoke(context))

            assertSame(ambient, success.context.event.error)
            assertEquals("changed", success.context.event.payload?.value)
        }

    @Test
    fun `cancellation and programming defects escape the owner`() =
        runTest {
            val failures =
                listOf(
                    CancellationException("cancelled"),
                    IllegalArgumentException("defect"),
                )

            failures.forEach { expected ->
                val owned =
                    OwnedExecution(
                        BoundExecution { throw expected },
                        continueHandler(Pipeline.empty),
                    )

                val thrown = assertFailsWith<Throwable> { owned.invoke(policyContext(this)) }
                assertSame(expected, thrown)
            }
        }

    private fun errorHandler(
        errorType: ErrorType = ErrorType.Mule.ANY,
        pipeline: Pipeline,
        kind: ResolvedErrorHandlerStrategy.Kind = ResolvedErrorHandlerStrategy.Kind.CONTINUE,
        whenExpression: String? = null,
    ): ResolvedErrorHandler =
        ResolvedErrorHandler(
            listOf(
                ResolvedErrorHandlerStrategy(
                    errorType = errorType,
                    pipeline = pipeline,
                    kind = kind,
                    whenExpression = whenExpression,
                ),
            ),
        )
}

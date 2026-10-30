package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ChoiceRoute
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.execution.policy.choicePolicyDeclaration
import dev.museframework.engine.execution.policy.flowRefPolicyDeclaration
import dev.museframework.engine.execution.policy.forEachPolicyDeclaration
import dev.museframework.engine.execution.policy.tryPolicyDeclaration
import dev.museframework.engine.execution.policy.untilSuccessfulPolicyDeclaration
import dev.museframework.engine.execution.policy.withCorrelationIdPolicyDeclaration
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.execution.policyError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class NestedPolicyOwnershipIT {
    @Test
    fun `S02 and S03 nested foreach choice and flow preserve local continue or propagate ownership`() = runTest {
        listOf(false, true).forEach { propagate ->
            val trace = mutableListOf<String>()
            val childError = policyError("CHILD")
            var childHandlerCalls = 0
            var outerHandlerCalls = 0
            var childHandlerScope: String? = null
            var childHandlerSource: Triple<String?, Int?, String?>? = null
            var outerHandlerScope: String? = null
            val childHandlerProcessor = CaptureConfig(
                docName = "child-handler",
                docId = "child-handler",
                sourceFile = "child-handler.xml",
                sourceLine = 41,
                sourceClass = "ChildHandler",
            )
            val child = MuseFlow.Flow(
                name = "child",
                docId = "child",
                pipeline = listOf(
                    CaptureConfig("child", "child"),
                    CaptureConfig("child-tail", "child-tail"),
                ),
                errorHandler = handler(childHandlerProcessor, propagate),
            )
            val choice = ChoiceConfig(
                docName = "choice",
                docId = "choice",
                whenRoutes = listOf(
                    ChoiceRoute(
                        expression = "#[true]",
                        pipeline = listOf(
                            CaptureConfig("before-call", "before-call"),
                            FlowRefConfig("child-call", "child-call", ref = FlowRef.ByReference(child)),
                            CaptureConfig("after-call", "after-call"),
                        ),
                    ),
                ),
                otherwise = null,
            )
            val root = policyFlow(
                body = listOf(
                    ForEachConfig(
                        docName = "foreach",
                        docId = "foreach",
                        collection = null,
                        pipeline = listOf(choice),
                    ),
                    CaptureConfig("after-foreach", "after-foreach"),
                ),
                handler = handler(CaptureConfig("outer", "outer")),
            )
            val factory = candidateFactory(
                mapOf(
                    "before-call" to { context ->
                        trace += "before-${context.event.payload?.value}"
                        ProcessorResult.Success(context)
                    },
                    "child" to { context ->
                        val item = context.event.payload?.value as Int
                        trace += "child-$item"
                        if (item == 1) {
                            ProcessorResult.Failure(
                                childError,
                                context.withEvent(
                                    context.event.copy(
                                        payload = TypedValue.of("failed-$item"),
                                        attributes = TypedValue.of("failed-attributes"),
                                        variables = context.event.variables + ("child" to TypedValue.of("mutated")),
                                    ),
                                ),
                            )
                        } else {
                            ProcessorResult.Success(
                                context.withEvent(
                                    context.event.copy(
                                        variables = context.event.variables + ("second" to TypedValue.of("visited")),
                                    ),
                                ),
                            )
                        }
                    },
                    "child-tail" to { context ->
                        trace += "child-tail-${context.event.payload?.value}"
                        ProcessorResult.Success(context)
                    },
                    "child-handler" to { context ->
                        childHandlerCalls++
                        trace += "child-handler"
                        childHandlerScope = context.currentScope?.name
                        childHandlerSource = Triple(
                            context.currentComponent?.sourceFile,
                            context.currentComponent?.sourceLine,
                            context.currentComponent?.sourceClass,
                        )
                        ProcessorResult.Success(
                            context.withEvent(
                                context.event.copy(
                                    payload = TypedValue.of("handler-payload"),
                                    attributes = TypedValue.of("handler-attributes"),
                                    variables = context.event.variables + ("handler" to TypedValue.of("mutated")),
                                ),
                            ),
                        )
                    },
                    "after-call" to { context ->
                        trace += "after-${context.event.payload?.value}"
                        ProcessorResult.Success(context)
                    },
                    "after-foreach" to { context ->
                        trace += "after-foreach"
                        ProcessorResult.Success(context)
                    },
                    "outer" to { context ->
                        outerHandlerCalls++
                        trace += "outer"
                        outerHandlerScope = context.currentScope?.name
                        ProcessorResult.Success(
                            context.withEvent(
                                context.event.copy(
                                    variables = context.event.variables + ("outer" to TypedValue.of("handled")),
                                ),
                            ),
                        )
                    },
                ),
            )

            val result = EngineFlowHarness(this, root, factory, additionalFlows = listOf(child)).execute(
                MuseEvent(
                    payload = TypedValue.of(listOf(1, 2)),
                    attributes = TypedValue.of("entry-attributes"),
                    variables = mapOf("entry" to TypedValue.of("kept")),
                    correlationId = "caller-correlation",
                ),
            )

            assertEquals(1, childHandlerCalls)
            assertEquals("child", childHandlerScope)
            assertEquals(Triple("child-handler.xml", 41, "ChildHandler"), childHandlerSource)
            if (!propagate) {
                assertEquals(0, outerHandlerCalls)
                assertEquals(
                    listOf(
                        "before-1", "child-1", "child-handler", "after-handler-payload",
                        "before-2", "child-2", "child-tail-2", "after-2", "after-foreach",
                    ),
                    trace,
                )
                assertEquals(listOf(1, 2), result.payload?.value)
                assertEquals("entry-attributes", result.attributes?.value)
                assertEquals("mutated", result.variables["handler"]?.value)
                assertEquals("visited", result.variables["second"]?.value)
                assertNull(result.error)
            } else {
                assertEquals(1, outerHandlerCalls)
                assertEquals("policy-contract", outerHandlerScope)
                assertEquals(listOf("before-1", "child-1", "child-handler", "outer"), trace)
                assertEquals("handler-payload", result.payload?.value)
                assertEquals("handler-attributes", result.attributes?.value)
                assertEquals("mutated", result.variables["child"]?.value)
                assertEquals("mutated", result.variables["handler"]?.value)
                assertEquals("handled", result.variables["outer"]?.value)
                assertEquals(1, result.variables["counter"]?.value)
                assertIs<MuseEvent>(result.variables["rootMessage"]?.value)
                assertNull(result.error)
            }
            assertEquals("caller-correlation", result.correlationId)
        }
    }

    @Test
    fun `S04 nested Try recovery restores the outer error before the handler continues`() = runTest {
        val outerError = policyError("E1")
        val innerError = policyError("E2")
        val trace = mutableListOf<String>()
        var followingError: MuseError? = null
        val nestedTry = TryConfig(
            docName = "try",
            docId = "try",
            pipeline = listOf(CaptureConfig("inner-fail", "inner-fail"), CaptureConfig("inner-tail", "inner-tail")),
            errorHandler = handler(CaptureConfig("inner-handler", "inner-handler")),
        )
        val root = policyFlow(
            body = listOf(CaptureConfig("outer-fail", "outer-fail")),
            handler = ErrorHandler(
                listOf(
                    ErrorHandlerStrategy.OnErrorContinue(
                        ErrorType.Mule.ANY,
                        listOf(nestedTry, CaptureConfig("following", "following")),
                    ),
                ),
            ),
        )
        val result = EngineFlowHarness(
            this,
            root,
            candidateFactory(
                mapOf(
                    "outer-fail" to { trace += "outer-fail"; ProcessorResult.Failure(outerError, it) },
                    "inner-fail" to { trace += "inner-fail"; ProcessorResult.Failure(innerError, it) },
                    "inner-tail" to { trace += "inner-tail"; ProcessorResult.Success(it) },
                    "inner-handler" to { trace += "inner-handler"; ProcessorResult.Success(it) },
                    "following" to {
                        trace += "following"
                        followingError = it.event.error
                        ProcessorResult.Success(it)
                    },
                ),
            ),
        ).execute()

        assertEquals(listOf("outer-fail", "inner-fail", "inner-handler", "following"), trace)
        assertSame(outerError, followingError)
        assertNull(result.error)
    }

    @Test
    fun `S05 retry exhausts before the outer handler and exposes the entry event once`() = runTest {
        val trace = mutableListOf<String>()
        val attemptErrors = mutableListOf<MuseError>()
        var attempts = 0
        var outerCalls = 0
        var outerEvent: MuseEvent? = null
        val retry = UntilSuccessfulConfig(
            docName = "retry",
            docId = "retry",
            maxRetries = 2,
            millisBetweenRetries = 5,
            pipeline = listOf(CaptureConfig("attempt", "attempt")),
        )
        val root = policyFlow(
            body = listOf(retry, CaptureConfig("tail", "tail")),
            handler = handler(CaptureConfig("outer", "outer")),
        )
        val input = MuseEvent(
            payload = TypedValue.of("entry"),
            attributes = TypedValue.of("entry-attributes"),
            variables = mapOf("seed" to TypedValue.of("kept")),
            correlationId = "caller",
        )
        val result = EngineFlowHarness(
            this,
            root,
            candidateFactory(
                mapOf(
                    "attempt" to { context ->
                        attempts++
                        trace += "attempt-$attempts"
                        policyError("attempt-$attempts").let { error ->
                            attemptErrors += error
                            ProcessorResult.Failure(
                                error,
                                context.withEvent(
                                    context.event.copy(
                                        payload = TypedValue.of("failed-$attempts"),
                                        attributes = TypedValue.of("failed-attributes"),
                                        variables = emptyMap(),
                                    ),
                                ),
                            )
                        }
                    },
                    "tail" to { trace += "tail"; ProcessorResult.Success(it) },
                    "outer" to { context ->
                        outerCalls++
                        trace += "outer"
                        outerEvent = context.event
                        ProcessorResult.Success(context)
                    },
                ),
            ),
        ).execute(input)

        assertEquals(listOf("attempt-1", "attempt-2", "attempt-3", "outer"), trace)
        assertEquals(3, attempts)
        assertEquals(1, outerCalls)
        assertEquals(ErrorType.Mule.RETRY_EXHAUSTED, outerEvent?.error?.errorType)
        assertEquals(listOf(attemptErrors.last()), outerEvent?.error?.childErrors)
        assertEquals("entry", result.payload?.value)
        assertEquals("entry-attributes", result.attributes?.value)
        assertEquals("kept", result.variables["seed"]?.value)
        assertEquals("caller", result.correlationId)
        assertNull(result.error)
    }

    @Test
    fun `S06 retry around foreach repeats the whole body`() = runTest {
        val trace = mutableListOf<Int>()
        var itemTwoCalls = 0
        val failure = policyError("ITEM")
        val foreach = forEach(listOf(CaptureConfig("item", "item")))
        val retry = retry(listOf(foreach), maxRetries = 1)
        val result = EngineFlowHarness(
            this,
            policyFlow(listOf(retry)),
            candidateFactory(
                mapOf(
                    "item" to { context ->
                        val item = context.event.payload?.value as Int
                        trace += item
                        if (item == 2 && itemTwoCalls++ == 0) ProcessorResult.Failure(failure, context)
                        else ProcessorResult.Success(context)
                    },
                ),
            ),
        ).execute(MuseEvent(payload = TypedValue.of(listOf(1, 2))))

        assertEquals(listOf(1, 2, 1, 2), trace)
        assertEquals(listOf(1, 2), result.payload?.value)
        assertNull(result.error)
    }

    @Test
    fun `S06 foreach around retry repeats only the current item`() = runTest {
        val trace = mutableListOf<Int>()
        var itemTwoCalls = 0
        val failure = policyError("ITEM")
        val retry = retry(listOf(CaptureConfig("item", "item")), maxRetries = 1)
        val foreach = forEach(listOf(retry))
        val result = EngineFlowHarness(
            this,
            policyFlow(listOf(foreach)),
            candidateFactory(
                mapOf(
                    "item" to { context ->
                        val item = context.event.payload?.value as Int
                        trace += item
                        if (item == 2 && itemTwoCalls++ == 0) ProcessorResult.Failure(failure, context)
                        else ProcessorResult.Success(context)
                    },
                ),
            ),
        ).execute(MuseEvent(payload = TypedValue.of(listOf(1, 2))))

        assertEquals(listOf(1, 2, 2), trace)
        assertEquals(listOf(1, 2), result.payload?.value)
        assertNull(result.error)
    }

    @Test
    fun `S10 target runs after local recovery and target failure stays with the caller`() = runTest {
        listOf(false, true).forEach { targetFails ->
            val trace = mutableListOf<String>()
            val childError = policyError("CHILD")
            var childCalls = 0
            var targetError: MuseError? = null
            val child = MuseFlow.Flow(
                name = "target-child",
                docId = "target-child",
                pipeline = listOf(CaptureConfig("child-fail", "child-fail")),
                errorHandler = handler(CaptureConfig("child-recover", "child-recover")),
            )
            val target = if (targetFails) {
                TargetConfig.of("answer", "#[payload.]")
            } else {
                TargetConfig.of("answer")
            }
            val root = policyFlow(
                body = listOf(
                    FlowRefConfig(
                        docName = "target-call",
                        docId = "target-call",
                        sourceFile = "caller.xml",
                        sourceLine = 73,
                        sourceClass = "CallerFlow",
                        ref = FlowRef.ByReference(child),
                        targetConfig = target,
                    ),
                    CaptureConfig("after-target", "after-target"),
                ),
                handler = handler(CaptureConfig("outer", "outer")),
            )
            val input = MuseEvent(
                payload = TypedValue.of("entry"),
                attributes = TypedValue.of("entry-attributes"),
                variables = mapOf("seed" to TypedValue.of("kept")),
                correlationId = "caller",
            )
            val result = EngineFlowHarness(
                this,
                root,
                candidateFactory(
                    mapOf(
                        "child-fail" to {
                            childCalls++
                            trace += "child-fail"
                            ProcessorResult.Failure(childError, it)
                        },
                        "child-recover" to { context ->
                            trace += "child-recover"
                            ProcessorResult.Success(
                                context.withEvent(
                                    context.event.copy(
                                        payload = TypedValue.of("recovered"),
                                        variables = context.event.variables + ("callee" to TypedValue.of("mutated")),
                                    ),
                                ),
                            )
                        },
                        "after-target" to { trace += "after-target"; ProcessorResult.Success(it) },
                        "outer" to { context ->
                            trace += "outer"
                            targetError = context.event.error
                            ProcessorResult.Success(context)
                        },
                    ),
                ),
                additionalFlows = listOf(child),
            ).execute(input)

            assertEquals(1, childCalls)
            assertEquals("entry", result.payload?.value)
            assertEquals("entry-attributes", result.attributes?.value)
            assertEquals("kept", result.variables["seed"]?.value)
            assertEquals("caller", result.correlationId)
            assertNull(result.error)
            if (targetFails) {
                assertEquals(listOf("child-fail", "child-recover", "outer"), trace)
                assertEquals(ErrorType.Mule.EXPRESSION, targetError?.errorType)
                assertEquals("caller.xml", targetError?.sourceFile)
                assertEquals(73, targetError?.sourceLine)
                assertEquals("CallerFlow", targetError?.sourceClass)
                assertEquals(setOf("seed"), result.variables.keys)
            } else {
                assertEquals(listOf("child-fail", "child-recover", "after-target"), trace)
                assertNull(targetError)
                assertEquals("recovered", result.variables["answer"]?.value)
                assertEquals(setOf("seed", "answer"), result.variables.keys)
            }
        }
    }

    @Test
    fun `retry declaration rejects negative retries and delay during bootstrap`() {
        listOf(
            retry(emptyList(), maxRetries = -1),
            retry(emptyList(), maxRetries = 0, delay = -1),
        ).forEach { invalid ->
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                dev.museframework.engine.app.bootstrap(
                    dev.museframework.engine.app.MuseApplicationConfig(
                        flows = listOf(policyFlow(listOf(invalid))),
                        factory = candidateFactory(),
                    ),
                )
            }
        }
    }

    private fun candidateFactory(
        actions: Map<String, suspend (dev.museframework.engine.api.execution.ExecutionContext) -> ProcessorResult> = emptyMap(),
    ): MuseComponentFactory = policyFactory(actions).also { factory ->
        factory.registerPolicyRouter(ChoiceConfig::class, choicePolicyDeclaration)
        factory.registerPolicyRouter(ForEachConfig::class, forEachPolicyDeclaration)
        factory.registerPolicyRouter(UntilSuccessfulConfig::class, untilSuccessfulPolicyDeclaration)
        factory.registerPolicyRouter(WithCorrelationIdConfig::class, withCorrelationIdPolicyDeclaration)
        factory.registerPolicyRouter(FlowRefConfig::class, flowRefPolicyDeclaration)
        factory.registerPolicyRouter(TryConfig::class, tryPolicyDeclaration)
    }

    private fun handler(
        processor: CaptureConfig,
        propagate: Boolean = false,
    ): ErrorHandler = ErrorHandler(
        listOf(
            if (propagate) {
                ErrorHandlerStrategy.OnErrorPropagate(ErrorType.Mule.ANY, listOf(processor))
            } else {
                ErrorHandlerStrategy.OnErrorContinue(ErrorType.Mule.ANY, listOf(processor))
            },
        ),
    )

    private fun forEach(body: List<dev.museframework.dsl.api.model.component.PipelineElement>): ForEachConfig =
        ForEachConfig("foreach", "foreach", collection = null, pipeline = body)

    private fun retry(
        body: List<dev.museframework.dsl.api.model.component.PipelineElement>,
        maxRetries: Int,
        delay: Long = 0,
    ): UntilSuccessfulConfig = UntilSuccessfulConfig(
        "retry",
        "retry",
        maxRetries = maxRetries,
        millisBetweenRetries = delay,
        pipeline = body,
    )
}

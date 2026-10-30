package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.MuseBootstrapException
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.factory.MuseEngineRegistrar
import org.junit.jupiter.api.Test
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.execution.policyError
import dev.museframework.test.policy.DefaultOnFailureConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertIs
import kotlin.test.assertTrue
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.dsl.api.model.config.Configuration
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyDefinition
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.execution.policyContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration
import kotlin.test.assertFailsWith

class PublicPolicyBindingIT {
    @Test
    fun `selected global handler is discovered even if its flow name is shadowed`() {
        val selected = MuseFlow.Flow("global", "selected", errorHandler = ErrorHandler(listOf(
            ErrorHandlerStrategy.OnErrorContinue(ErrorType.Mule.ANY, listOf(
                FlowRefConfig("missing", "missing", ref = FlowRef.ByName("missing")),
            )),
        )))
        val shadow = MuseFlow.Flow("global", "shadow")
        assertFailsWith<MuseBootstrapException> {
            bootstrap(MuseApplicationConfig(listOf(selected, shadow),
                listOf(Configuration("configuration", defaultErrorHandlerRef = "global")), policyFactory()))
        }
    }

    @Test
    fun `occurrence bindings stay with their legacy parent when a referenced flow is prepared later`() = runTest {
        var declared = 0
        val seen = mutableListOf<Int>()
        val reused = ProbePolicyConfig { _, _ ->
            val occurrence = ++declared
            PolicyFactory { ExecutionPolicy { seen += occurrence; PolicyOutcome.Success(it.input) } }
        }
        val child = MuseFlow.SubFlow("child", "child", pipeline = listOf(reused))
        val flow = policyFlow(listOf(
            FlowRefConfig("child", "child", ref = FlowRef.ByName("child")),
            TryConfig("try", "try", pipeline = listOf(reused), errorHandler = null),
        ))
        EngineFlowHarness(this, flow, policyFactory(), additionalFlows = listOf(child)).execute()
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `prepared registration snapshot rejects mutation and ignores later composition changes`() = runTest {
        val config = ProbePolicyConfig { definition, _ ->
            val body = definition.inline(listOf(CaptureConfig("capture", "capture")))
            PolicyFactory { ExecutionPolicy { it.call(body) } }
        }
        val original = policyFactory(mapOf("capture" to {
            ProcessorResult.Success(it.withEvent(it.event.copy(payload = TypedValue.of("original" as Any))))
        }))
        val flow = policyFlow(listOf(config))
        val app = bootstrap(MuseApplicationConfig(listOf(flow), factory = original))
        try {
            assertFailsWith<IllegalStateException> { MuseEngineRegistrar().register(app.context.factory) }
            original.registerProcessor(CaptureConfig::class) { component, _ ->
                CaptureMuseProcessor(component) { error("Changed composition must not affect the application") }
            }
            app.init()
            val result = app.context.policyBindings!!.pipeline(flow).elements.single().processor.process(policyContext(this))
            assertEquals("original", assertIs<ProcessorResult.Success>(result).context.event.payload?.value)
        } finally { app.stop() }
    }

    @Test
    fun `concurrent requests reuse one policy with independent invocations and local owner state`() = runTest {
        var creations = 0
        var handlers = 0
        val failure = policyError("FAIL")
        val config = ProbePolicyConfig { definition, _ ->
            val body = definition.inline(listOf(CaptureConfig("fail", "fail")))
            definition.localHandler(handler("local"))
            PolicyFactory {
                creations++
                ExecutionPolicy { invocation ->
                    val initial = invocation.input
                    invocation.pause(1)
                    assertSame(initial, invocation.input)
                    invocation.call(body)
                }
            }
        }
        withPreparedPolicy(config, policyFactory(mapOf(
            "fail" to { delay(2); ProcessorResult.Failure(failure, it) },
            "local" to { handlers++; delay(1); ProcessorResult.Success(it) },
        ))) { _, processor ->
            val outcomes = (1..20).map { index -> async {
                processor.process(policyContext(this, MuseEvent(payload = TypedValue.of(index as Any))))
            } }.awaitAll()
            assertEquals((1..20).toList(), outcomes.map { assertIs<ProcessorResult.Success>(it).context.event.payload?.value })
            assertEquals(20, handlers)
            assertEquals(1, creations)
        }
    }

    @Test
    fun `foreign application handles and a stopped application processor are rejected`() = runTest {
        lateinit var foreign: BodyHandle
        val origin = ProbePolicyConfig { definition, _ ->
            foreign = definition.inline(emptyList())
            PolicyFactory { ExecutionPolicy { it.call(foreign) } }
        }
        withPreparedPolicy(origin) { app, processor ->
            val other = ProbePolicyConfig { _, _ -> PolicyFactory { ExecutionPolicy { it.call(foreign) } } }
            withPreparedPolicy(other) { _, otherProcessor ->
                assertFailsWith<IllegalArgumentException> { otherProcessor.process(policyContext(this)) }
            }
            app.stop()
            assertFailsWith<IllegalStateException> { processor.process(policyContext(this)) }
        }
    }

    @Test
    fun `invocation expires when execute returns even while application remains active`() = runTest {
        lateinit var retained: PolicyInvocation
        lateinit var handle: BodyHandle
        val config = ProbePolicyConfig { definition, _ ->
            handle = definition.inline(emptyList())
            PolicyFactory { ExecutionPolicy { retained = it; it.call(handle) } }
        }
        withPreparedPolicy(config) { _, processor ->
            processor.process(policyContext(this))
            assertFailsWith<IllegalStateException> { retained.call(handle, MuseEvent()) }
            assertFailsWith<IllegalStateException> { retained.manage(PolicyTrackingProvider()) }
        }
    }

    @Test
    fun `policy resources are retained immediately and released when its algorithm throws`() = runTest {
        val provider = PolicyTrackingProvider()
        val defect = IllegalArgumentException("plugin defect")
        val config = ProbePolicyConfig { _, _ -> PolicyFactory { ExecutionPolicy {
            assertSame(provider, it.manage(provider))
            assertTrue(provider.isManaged)
            throw defect
        } } }
        val exception = assertFailsWith<IllegalArgumentException> {
            EngineFlowHarness(this, policyFlow(listOf(config)), policyFactory()).execute()
        }
        assertEquals("plugin defect", exception.message)
        assertTrue(provider.isClosed)
        assertEquals(1, provider.releases)
    }

    @Test
    fun `returned policy resource graph is registered automatically`() = runTest {
        val provider = PolicyTrackingProvider()
        val config = ProbePolicyConfig { _, _ -> PolicyFactory { ExecutionPolicy {
            PolicyOutcome.Success(it.input.copy(variables = mapOf("resource" to TypedValue.of(provider as Any))))
        } } }
        EngineFlowHarness(this, policyFlow(listOf(config)), policyFactory()).execute()
        assertTrue(provider.isClosed)
        assertEquals(1, provider.releases)
    }

    @Test
    fun `managed handler bodies are declared after configurations and validate unused references`() {
        var declared = false
        val nested = ProbePolicyConfig { definition, context ->
            assertEquals("ready", context.properties.getProperty("phase"))
            declared = true
            definition.flow(FlowRef.ByName("missing"))
            PolicyFactory { ExecutionPolicy { PolicyOutcome.Success(it.input) } }
        }
        val owner = ProbePolicyConfig { definition, _ ->
            definition.localHandler(ErrorHandler(listOf(ErrorHandlerStrategy.OnErrorContinue(ErrorType.Mule.ANY, listOf(nested)))))
            PolicyFactory { ExecutionPolicy { PolicyOutcome.Success(it.input) } }
        }
        assertFailsWith<MuseBootstrapException> {
            bootstrap(MuseApplicationConfig(listOf(policyFlow(listOf(owner))),
                listOf(GlobalProperty(docName = "phase", name = "phase", value = "ready")), policyFactory()))
        }
        assertTrue(declared)
    }

    @Test
    fun `local handler can only be declared once`() {
        val config = ProbePolicyConfig { definition, _ ->
            definition.localHandler(handler("first"))
            definition.localHandler(handler("second"))
            PolicyFactory { ExecutionPolicy { PolicyOutcome.Success(it.input) } }
        }
        assertFailsWith<IllegalStateException> {
            bootstrap(MuseApplicationConfig(listOf(policyFlow(listOf(config))), factory = policyFactory()))
        }
    }

    @Test
    fun `dynamic invalid durations and body cancellation remain contract exceptions`() = runTest {
        for (mode in listOf("pause", "timeout", "cancel", "defect")) {
            val defect = IllegalArgumentException("defect")
            val config = ProbePolicyConfig { definition, _ ->
                val body = definition.inline(listOf(CaptureConfig("body", "body")))
                PolicyFactory { ExecutionPolicy { invocation ->
                    if (mode == "pause") invocation.pause(-1)
                    invocation.call(body, timeoutMillis = if (mode == "timeout") 0 else null)
                } }
            }
            val factory = policyFactory(mapOf("body" to {
                if (mode == "cancel") throw CancellationException("request cancelled")
                throw defect
            }))
            withPreparedPolicy(config, factory) { _, processor ->
                if (mode == "cancel") assertFailsWith<CancellationException> { processor.process(policyContext(this)) }
                else {
                    val exception = assertFailsWith<IllegalArgumentException> { processor.process(policyContext(this)) }
                    if (mode == "defect") assertEquals("defect", exception.message)
                }
            }
        }
    }

    @Test
    fun `candidate Try recovery completes before external policy observes its body outcome`() = runTest {
        val failure = policyError("ELIGIBLE")
        val markers = mutableListOf<String>()
        val nested = TryConfig("try", "try", pipeline = listOf(CaptureConfig("fail", "fail"), CaptureConfig("tail", "tail")),
            errorHandler = handler("local"))
        val config = DefaultOnFailureConfig(listOf(nested), setOf(failure.errorType),
            TypedValue.of("replacement" as Any), "default", "default")
        val factory = policyFactory(mapOf(
            "fail" to { markers += "fail"; ProcessorResult.Failure(failure, it) },
            "tail" to { markers += "tail"; ProcessorResult.Success(it) },
            "local" to { markers += "local"; ProcessorResult.Success(it.withEvent(it.event.copy(payload = TypedValue.of("handled" as Any)))) },
        ))
        factory.registerPolicyRouter(TryConfig::class, PolicyRouterDeclaration { component, definition, _ ->
            val body = definition.inline(component.pipeline)
            component.errorHandler?.let(definition::localHandler)
            PolicyFactory { ExecutionPolicy { it.call(body) } }
        })
        val result = EngineFlowHarness(this, policyFlow(listOf(config)), factory).execute()
        assertEquals("handled", result.payload?.value)
        assertEquals(listOf("fail", "local"), markers)
    }

    @Test
    fun `legacy recursive references prepare without eager recursion`() = runTest {
        val recursive = MuseFlow.SubFlow("recursive", "recursive", pipeline =
            listOf(FlowRefConfig("recurse", "recurse", ref = FlowRef.ByName("recursive"))))
        val app = bootstrap(MuseApplicationConfig(listOf(recursive), factory = policyFactory()))
        try { app.init() } finally { app.stop() }
    }

    @Test
    fun `unused declared branch validates nested references before init`() {
        val config = ProbePolicyConfig { definition, _ ->
            definition.inline(listOf(FlowRefConfig("missing", "missing", ref = FlowRef.ByName("missing"))))
            PolicyFactory { ExecutionPolicy { PolicyOutcome.Success(it.input) } }
        }
        assertFailsWith<MuseBootstrapException> {
            bootstrap(MuseApplicationConfig(listOf(policyFlow(listOf(config))), factory = policyFactory()))
        }
    }

    @Test
    fun `declarations observe configurations and create once per reused occurrence during init`() = runTest {
        var declarations = 0
        var creations = 0
        val config = ProbePolicyConfig { definition, context ->
            assertEquals("ready", context.properties.getProperty("phase"))
            declarations++
            val body = definition.inline(emptyList())
            PolicyFactory {
                creations++
                ExecutionPolicy { invocation -> invocation.call(body) }
            }
        }
        val app = bootstrap(MuseApplicationConfig(
            listOf(policyFlow(listOf(config, config))),
            configs = listOf(GlobalProperty(docName = "phase", name = "phase", value = "ready")),
            factory = policyFactory(),
        ))
        assertEquals(2, declarations)
        assertEquals(0, creations)
        try {
            app.init()
            assertEquals(2, creations)
        } finally { app.stop() }
    }

    @Test
    fun `recursive named declarations link at init without executing a body`() = runTest {
        var executions = 0
        var declarations = 0
        val recursive = ProbePolicyConfig { definition, _ ->
            declarations++
            definition.flow(FlowRef.ByName("recursive"))
            PolicyFactory { ExecutionPolicy { executions++; PolicyOutcome.Success(it.input) } }
        }
        val flow = policyFlow(listOf(recursive)).copy(name = "recursive")
        val app = bootstrap(MuseApplicationConfig(listOf(flow), factory = policyFactory()))
        assertEquals(1, declarations)
        try { app.init(); assertEquals(0, executions) } finally { app.stop() }
    }

    @Test
    fun `typed reference values sharing a name retain their distinct bodies`() = runTest {
        val markers = mutableListOf<String>()
        val first = MuseFlow.SubFlow("same", "first", pipeline = listOf(CaptureConfig("first", "first")))
        val second = MuseFlow.SubFlow("same", "second", pipeline = listOf(CaptureConfig("second", "second")))
        val config = ProbePolicyConfig { definition, _ ->
            val a = definition.flow(FlowRef.ByReference(first))
            val b = definition.flow(FlowRef.ByReference(second))
            PolicyFactory { ExecutionPolicy { invocation -> invocation.call(a); invocation.call(b) } }
        }
        val factory = policyFactory(mapOf(
            "first" to { markers += "first"; ProcessorResult.Success(it) },
            "second" to { markers += "second"; ProcessorResult.Success(it) },
        ))
        EngineFlowHarness(this, policyFlow(listOf(config)), factory,
            additionalFlows = listOf(MuseFlow.SubFlow("same", "registered"))).execute()
        assertEquals(listOf("first", "second"), markers)
    }

    @Test
    fun `flow fallback owns the global handler while subflow failure remains transparent`() = runTest {
        for (subflow in listOf(false, true)) {
            val markers = mutableListOf<String>()
            val failure = policyError("FAIL")
            val child = if (subflow) MuseFlow.SubFlow("child", "child", pipeline = listOf(CaptureConfig("fail", "fail")))
                else MuseFlow.Flow("child", "child", pipeline = listOf(CaptureConfig("fail", "fail")))
            val global = policyFlow(emptyList(), handler("global")).copy(name = "global")
            val config = ProbePolicyConfig { definition, _ ->
                val body = definition.flow(FlowRef.ByReference(child))
                PolicyFactory { ExecutionPolicy { invocation ->
                    val result = invocation.call(body)
                    markers += if (result is PolicyOutcome.Success) "success" else "failure"
                    result
                } }
            }
            val factory = policyFactory(mapOf(
                "fail" to { ProcessorResult.Failure(failure, it) },
                "global" to { markers += "global"; ProcessorResult.Success(it) },
                "outer" to { markers += "outer"; ProcessorResult.Success(it) },
            ))
            EngineFlowHarness(this, policyFlow(listOf(config), handler("outer")), factory,
                configs = listOf(Configuration("configuration", defaultErrorHandlerRef = "global")),
                additionalFlows = listOf(global)).execute()
            assertEquals(if (subflow) listOf("failure", "outer") else listOf("global", "success"), markers)
        }
    }

    @Test
    fun `local owner handles only the complete algorithm failure`() = runTest {
        val failure = policyError("FAIL")
        val markers = mutableListOf<String>()
        val config = ProbePolicyConfig { definition, _ ->
            val body = definition.inline(listOf(CaptureConfig("fail", "fail")))
            definition.localHandler(handler("local"))
            PolicyFactory { ExecutionPolicy { invocation ->
                invocation.call(body)
                markers += "between"
                invocation.call(body)
            } }
        }
        val factory = policyFactory(mapOf(
            "fail" to { markers += "fail"; ProcessorResult.Failure(failure, it) },
            "local" to { markers += "local"; ProcessorResult.Success(it) },
            "outer" to { markers += "outer"; ProcessorResult.Success(it) },
        ))
        EngineFlowHarness(this, policyFlow(listOf(config), handler("outer")), factory).execute()
        assertEquals(listOf("fail", "between", "fail", "local"), markers)
    }

    @Test
    fun `external ineligible failure retains the full failed event and invokes outer handler once`() = runTest {
        val failure = policyError("INELIGIBLE")
        var observed: MuseEvent? = null
        var outerCalls = 0
        val config = DefaultOnFailureConfig(listOf(CaptureConfig("fail", "fail")),
            setOf(ErrorType.Mule.ANY), TypedValue.of("replacement" as Any), "default", "default")
        val factory = policyFactory(mapOf(
            "fail" to { ProcessorResult.Failure(failure, it.withEvent(it.event.copy(
                payload = TypedValue.of("failed" as Any),
                attributes = TypedValue.of("attributes" as Any),
                variables = mapOf("changed" to TypedValue.of(42 as Any)),
            ))) },
            "outer" to { outerCalls++; observed = it.event; ProcessorResult.Success(it) },
        ))
        val result = EngineFlowHarness(this, policyFlow(listOf(config), handler("outer")), factory)
            .execute(MuseEvent(correlationId = "correlation"))
        assertEquals(1, outerCalls)
        assertSame(failure, observed?.error)
        assertEquals("failed", result.payload?.value)
        assertEquals("attributes", result.attributes?.value)
        assertEquals(42, result.variables["changed"]?.value)
        assertEquals("correlation", result.correlationId)
    }

    @Test
    fun `fake and foreign binding handles are rejected`() = runTest {
        var foreign: BodyHandle? = null
        val first = ProbePolicyConfig { definition, _ ->
            foreign = definition.inline(emptyList())
            PolicyFactory { ExecutionPolicy { PolicyOutcome.Success(it.input) } }
        }
        for (fake in listOf(false, true)) {
            val second = ProbePolicyConfig { _, _ ->
                PolicyFactory { ExecutionPolicy { it.call(if (fake) object : BodyHandle {} else foreign!!) } }
            }
            assertFailsWith<IllegalArgumentException> {
                EngineFlowHarness(this, policyFlow(listOf(first, second)), policyFactory()).execute()
            }
        }
    }

    @Test
    fun `closed definitions and expired invocations reject subsequent use`() = runTest {
        lateinit var savedDefinition: PolicyDefinition
        lateinit var savedInvocation: PolicyInvocation
        lateinit var savedBody: BodyHandle
        val config = ProbePolicyConfig { definition, _ ->
            savedDefinition = definition
            savedBody = definition.inline(emptyList())
            PolicyFactory { ExecutionPolicy { savedInvocation = it; it.call(savedBody) } }
        }
        EngineFlowHarness(this, policyFlow(listOf(config)), policyFactory()).execute()
        assertFailsWith<IllegalStateException> { savedDefinition.inline(emptyList()) }
        assertFailsWith<IllegalStateException> { savedDefinition.flow(FlowRef.ByName("policy-contract")) }
        assertFailsWith<IllegalStateException> { savedDefinition.localHandler(handler("late")) }
        assertFailsWith<IllegalStateException> { savedInvocation.input }
        assertFailsWith<IllegalStateException> { savedInvocation.call(savedBody, MuseEvent()) }
        assertFailsWith<IllegalStateException> { savedInvocation.pause(0) }
        assertFailsWith<IllegalStateException> {
            savedInvocation.project(PolicyOutcome.Success(MuseEvent()), TargetConfig.of("target"))
        }
    }

    @Test
    fun `call converts only its own configured timeout and propagates outer timeout`() = runTest {
        for (outer in listOf(false, true)) {
            val config = ProbePolicyConfig { definition, _ ->
                val body = definition.inline(listOf(CaptureConfig("slow", "slow")))
                PolicyFactory { ExecutionPolicy { invocation ->
                    if (outer) withTimeout(5) { invocation.call(body, timeoutMillis = 100) }
                    else invocation.call(body, timeoutMillis = 5)
                } }
            }
            val harness = EngineFlowHarness(this, policyFlow(listOf(config)), policyFactory(mapOf(
                "slow" to { delay(20); ProcessorResult.Success(it) },
            )))
            if (outer) assertFailsWith<TimeoutCancellationException> { harness.execute() }
            else assertEquals(ErrorType.Mule.TIMEOUT, harness.execute().error?.errorType)
        }
    }

    private fun handler(id: String) = ErrorHandler(listOf(
        ErrorHandlerStrategy.OnErrorContinue(ErrorType.Mule.ANY, listOf(CaptureConfig(id, id))),
    ))

    @Test
    fun `external policy replaces eligible failure through canonical body execution`() = runTest {
        val error = policyError("ELIGIBLE")
        val config = DefaultOnFailureConfig(
            body = listOf(CaptureConfig("fail", "fail"), CaptureConfig("tail", "tail")),
            eligibleErrorTypes = setOf(error.errorType),
            replacement = TypedValue.of("replacement" as Any),
            docName = "default",
            docId = "default",
        )
        val markers = mutableListOf<String>()
        val factory = policyFactory(mapOf(
            "fail" to { context ->
                markers += "fail"
                ProcessorResult.Failure(error, context.withEvent(context.event.copy(
                    attributes = TypedValue.of("failed attributes" as Any), variables = emptyMap(),
                )))
            },
            "tail" to { context -> markers += "tail"; ProcessorResult.Success(context) },
        ))
        val result = EngineFlowHarness(this, policyFlow(listOf(config)), factory).execute(MuseEvent(
            attributes = TypedValue.of("entry attributes" as Any),
            variables = mapOf("entry" to TypedValue.of("value" as Any)),
        ))
        assertEquals("replacement", result.payload?.value)
        assertNull(result.error)
        assertEquals(listOf("fail"), markers)
        assertEquals("entry attributes", result.attributes?.value)
        assertEquals("value", result.variables["entry"]?.value)
    }

    @Test
    fun `named references share one prepared body and retain full local recovery`() = runTest {
        var creates = 0
        val markers = mutableListOf<String>()
        val error = policyError("CHILD")
        val childPolicy = ProbePolicyConfig { definition, _ ->
            val body = definition.inline(listOf(CaptureConfig("fail", "fail"), CaptureConfig("tail", "tail")))
            PolicyFactory { creates++; ExecutionPolicy { it.call(body) } }
        }
        val child = MuseFlow.Flow("child", "child", pipeline = listOf(childPolicy), errorHandler = handler("recover"))
        val root = ProbePolicyConfig { definition, _ ->
            val first = definition.flow(FlowRef.ByName("child"))
            val second = definition.flow(FlowRef.ByName("child"))
            PolicyFactory { ExecutionPolicy {
                assertIs<PolicyOutcome.Success>(it.call(first))
                markers += "between"
                it.call(second)
            } }
        }
        val result = EngineFlowHarness(this, policyFlow(listOf(root)), policyFactory(mapOf(
            "fail" to { markers += "fail"; ProcessorResult.Failure(error, it) },
            "tail" to { markers += "tail"; ProcessorResult.Success(it) },
            "recover" to { markers += "recover"; ProcessorResult.Success(it.withEvent(it.event.copy(payload = TypedValue.of("recovered" as Any)))) },
        )), additionalFlows = listOf(child)).execute()
        assertEquals(1, creates)
        assertEquals(listOf("fail", "recover", "between", "fail", "recover"), markers)
        assertEquals("recovered", result.payload?.value)
    }

    @Test
    fun `external recovery inside managed handler restores the original ambient error`() = runTest {
        val outerError = policyError("OUTER")
        val innerError = policyError("INNER")
        var followingError: dev.museframework.engine.api.error.MuseError? = null
        val replacement = DefaultOnFailureConfig(listOf(CaptureConfig("inner", "inner")),
            setOf(innerError.errorType), TypedValue.of("replacement" as Any), "default", "default")
        val root = ProbePolicyConfig { definition, _ ->
            val body = definition.inline(listOf(CaptureConfig("outer", "outer")))
            definition.localHandler(ErrorHandler(listOf(ErrorHandlerStrategy.OnErrorContinue(
                ErrorType.Mule.ANY, listOf(replacement, CaptureConfig("following", "following")),
            ))))
            PolicyFactory { ExecutionPolicy { it.call(body) } }
        }
        val result = EngineFlowHarness(this, policyFlow(listOf(root)), policyFactory(mapOf(
            "outer" to { ProcessorResult.Failure(outerError, it) },
            "inner" to { ProcessorResult.Failure(innerError, it) },
            "following" to { followingError = it.event.error; ProcessorResult.Success(it) },
        ))).execute()
        assertSame(outerError, followingError)
        assertNull(result.error)
        assertEquals("replacement", result.payload?.value)
    }

    @Test
    fun `bound flow default handler leaves failure for the enclosing owner`() = runTest {
        val failure = policyError("DEFAULT")
        var outerCalls = 0
        val child = MuseFlow.Flow("child", "child", pipeline = listOf(CaptureConfig("fail", "fail")))
        val config = ProbePolicyConfig { definition, _ ->
            val body = definition.flow(FlowRef.ByReference(child))
            PolicyFactory { ExecutionPolicy {
                val result = assertIs<PolicyOutcome.Failure>(it.call(body))
                assertSame(failure, result.error)
                result
            } }
        }
        EngineFlowHarness(this, policyFlow(listOf(config), handler("outer")), policyFactory(mapOf(
            "fail" to { ProcessorResult.Failure(failure, it) },
            "outer" to { outerCalls++; ProcessorResult.Success(it) },
        ))).execute()
        assertEquals(1, outerCalls)
    }

    @Test
    fun `unknown reference in an inactive flow handler fails during bootstrap`() {
        val handler = ErrorHandler(listOf(ErrorHandlerStrategy.OnErrorContinue(
            ErrorType.Mule.ANY,
            listOf(FlowRefConfig("missing", "missing", ref = FlowRef.ByName("missing"))),
        )))
        val flow = MuseFlow.Flow(name = "inactive", docId = "inactive", errorHandler = handler)
        val factory = MuseComponentFactory().also(MuseEngineRegistrar()::register)

        assertFailsWith<MuseBootstrapException> {
            bootstrap(MuseApplicationConfig(flows = listOf(flow), factory = factory))
        }
    }
}

package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.*
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.validation.ValidationError
import dev.museframework.engine.api.execution.Pipeline
import dev.museframework.engine.api.execution.PipelineElement
import dev.museframework.engine.api.execution.PipelineScope
import dev.museframework.engine.api.execution.ResolvedErrorHandler
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.BackgroundHandle
import dev.museframework.engine.api.execution.policy.PolicyDefinition
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.app.MuseBootstrapException
import dev.museframework.engine.app.resolve
import dev.museframework.engine.context.MuseContext
import dev.museframework.engine.execution.BoundExecution
import dev.museframework.engine.execution.OwnedExecution
import dev.museframework.engine.execution.runUnhandled
import java.util.IdentityHashMap
import dev.museframework.dsl.api.model.component.PipelineElement as DeclarationElement

/** Application-local declaration cells. Only Pipeline is executable; cells link recursive references at init. */
internal class PolicyBindings(
    private val context: MuseContext,
    private val globalHandlerFlow: MuseFlow.Flow?,
    val background: BackgroundTaskManager,
) {
    private val flows = IdentityHashMap<MuseFlow, FlowCell>()
    private val flowOrder = mutableListOf<FlowCell>()
    private var preparingPolicies: MutableList<PolicyCell>? = null
    private val policyOrder = mutableListOf<PolicyCell>()
    @Volatile private var active = true
    private var prepared = false

    fun discover() {
        context.flows.values.forEach(::discoverFlow)
        globalHandlerFlow?.let(::discoverFlow)
    }

    /**
     * Links every prepared occurrence at init.
     *
     * Handler bodies are compiled here, after discovery is complete, and the global handler
     * definition is resolved before any flow links its own owner: no body is ever compiled
     * recursively against a partially initialized context, and a handler body may itself contain
     * managed policies like any other body.
     */
    fun prepare() {
        checkActive()
        if (prepared) return
        flowOrder.forEach { it.prepare() }
        context.globalErrorHandler = globalHandlerFlow?.let { flows.getValue(it).localHandler }
        flowOrder.forEach { it.link() }
        policyOrder.forEach { it.prepare() }
        prepared = true
    }

    fun close() { active = false }

    fun checkActive() { check(active) { "Policy application is closed" } }

    fun pipeline(flow: MuseFlow): Pipeline = flows.getValue(flow).body.prepare()

    /** The prepared flow owner: its body traversal plus its resolved local/global/default handler. */
    fun execution(flow: MuseFlow): BoundExecution = flows.getValue(flow).execution

    fun localHandler(flow: MuseFlow): ResolvedErrorHandler? = flows.getValue(flow).localHandler

    fun resolvedHandler(flow: MuseFlow.Flow): ResolvedErrorHandler =
        localHandler(flow) ?: context.globalErrorHandler ?: context.defaultErrorHandler

    fun reference(reference: FlowRef): MuseFlow = when (reference) {
        is FlowRef.ByReference -> reference.flow
        is FlowRef.ByName -> context.flows[reference.name] ?: throw MuseBootstrapException(
            listOf(ValidationError("Flow reference '${reference.name}' not found in registered flows")),
        )
    }

    fun router(component: Router): PipelineElement {
        checkActive()
        val occurrence = preparingPolicies?.firstOrNull { it.component === component }
            ?: error("Policy router was not declared at bootstrap")
        preparingPolicies!!.remove(occurrence)
        return occurrence.prepare()
    }

    private fun discoverFlow(flow: MuseFlow): FlowCell {
        flows[flow]?.let { return it }
        // Insert before traversal: both named and typed recursive references find this cell.
        val cell = FlowCell(flow)
        flows[flow] = cell
        flowOrder += cell
        cell.body = BodyCell(flow.pipeline)
        cell.handler = (flow as? MuseFlow.Flow)?.errorHandler?.let(::HandlerCell)
        return cell
    }

    private fun discoverPipeline(elements: List<DeclarationElement>): Map<Int, List<PolicyCell>> =
        buildMap {
            elements.forEachIndexed { index, component ->
                val declaration = (component as? Router)?.let { context.factory.policyDeclaration(it) }
                if (declaration != null) {
                    val cell = PolicyCell(component as Router)
                    policyOrder += cell
                    put(index, listOf(cell))
                    try {
                        cell.factory = declaration.declare(component, cell, context)
                    } finally {
                        cell.open = false
                    }
                } else {
                    // Legacy shapes still need complete bootstrap validation while their registrations remain legacy.
                    fun nested(body: List<DeclarationElement>) = discoverPipeline(body).values.flatten()
                    val children = when (component) {
                        is FlowRefConfig -> {
                            discoverFlow(reference(component.ref))
                            emptyList()
                        }
                        is TryConfig -> {
                            nested(component.pipeline) +
                                component.errorHandler?.strategies.orEmpty().flatMap { nested(it.pipeline) }
                        }
                        is ChoiceConfig -> {
                            component.whenRoutes.flatMap { nested(it.pipeline) } +
                                component.otherwise?.let(::nested).orEmpty()
                        }
                        is ScatterGatherConfig -> component.routes.flatMap { nested(it.pipeline) }
                        is ForEachConfig -> nested(component.pipeline)
                        is ParallelForEachConfig -> nested(component.pipeline)
                        is UntilSuccessfulConfig -> nested(component.pipeline)
                        is WithCorrelationIdConfig -> nested(component.pipeline)
                        is AsyncConfig -> nested(component.pipeline)
                        else -> emptyList()
                    }
                    if (children.isNotEmpty()) put(index, children)
                }
            }
        }

    private inner class BodyCell(elements: List<DeclarationElement>) {
        private val elements = elements.toList()
        private val policies = discoverPipeline(this.elements)
        private var pipeline: Pipeline? = null

        fun prepare(): Pipeline = pipeline ?: Pipeline(elements.mapIndexed { index, element ->
            // A legacy builder may request its nested managed routers in any order. Restrict
            // matching to this parent occurrence, never another body sharing a config object.
            val previous = preparingPolicies
            preparingPolicies = policies[index].orEmpty().toMutableList()
            try {
                when (element) {
                    is Processor -> context.factory.createProcessor(element, context) as PipelineElement
                    is Router -> context.factory.createRouter(element, context) as PipelineElement
                    else -> error("Unknown pipeline element: ${element::class}")
                }
            } finally {
                preparingPolicies = previous
            }
        }).also { pipeline = it }
    }

    private inner class HandlerCell(private val handler: ErrorHandler) {
        private val bodies = handler.strategies.map { BodyCell(it.pipeline) }
        fun prepare(): ResolvedErrorHandler =
            handler.resolve { _, index -> bodies[index].prepare() }
    }

    private inner class FlowCell(val flow: MuseFlow) {
        lateinit var body: BodyCell
        var handler: HandlerCell? = null
        var localHandler: ResolvedErrorHandler? = null
        lateinit var execution: BoundExecution

        fun prepare() {
            body.prepare()
            localHandler = handler?.prepare()
        }

        fun link() {
            val scope = when (flow) {
                is MuseFlow.Flow -> PipelineScope.FlowScope(flow)
                is MuseFlow.SubFlow -> PipelineScope.SubFlowScope(flow)
            }
            execution = if (flow is MuseFlow.Flow) {
                val raw = BoundExecution { it.runUnhandled(body.prepare()) }
                val owned = OwnedExecution(raw, resolvedHandler(flow))
                BoundExecution { owned.invoke(it.withScope(scope)) }
            } else {
                BoundExecution { it.withScope(scope).runUnhandled(body.prepare()) }
            }
        }
    }

    private inner class PolicyCell(val component: Router) : PolicyDefinition {
        var open = true
        lateinit var factory: PolicyFactory
        private val identity = Any()
        private val bodies = mutableListOf<BodyCell>()
        private var handler: HandlerCell? = null
        private var adapter: PipelineElement? = null

        override fun inline(elements: List<DeclarationElement>): BodyHandle {
            checkOpen()
            val body = BodyCell(elements)
            bodies += body
            return BoundBody(this@PolicyBindings, identity, BoundExecution { it.runUnhandled(body.prepare()) })
        }

        override fun flow(reference: FlowRef): BodyHandle {
            checkOpen()
            val cell = discoverFlow(reference(reference))
            return BoundBody(this@PolicyBindings, identity, BoundExecution { cell.execution.invoke(it) })
        }

        override fun localHandler(handler: ErrorHandler) {
            checkOpen()
            check(this.handler == null) { "A policy may declare at most one local handler" }
            this.handler = HandlerCell(handler)
        }

        override fun background(maxConcurrency: Int?, timeoutMillis: Long?): BackgroundHandle {
            checkOpen()
            require(maxConcurrency == null || maxConcurrency > 0) { "Background concurrency must be positive" }
            require(timeoutMillis == null || timeoutMillis > 0) { "Background timeout must be positive" }
            return BoundBackgroundHandle(this@PolicyBindings, identity, maxConcurrency, timeoutMillis)
        }

        private fun checkOpen() {
            checkActive()
            check(open) { "Policy definition is closed" }
        }

        fun prepare(): PipelineElement = adapter ?: run {
            bodies.forEach { it.prepare() }
            val resolved = handler?.prepare()
            PipelineElement(PolicyProcessorAdapter(
                component, this@PolicyBindings, identity, factory.create(context), resolved,
            )).also { adapter = it }
        }
    }
}

internal class BoundBody(
    val application: PolicyBindings,
    val binding: Any,
    val execution: BoundExecution,
) : BodyHandle

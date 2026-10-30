package dev.museframework.engine.factory

import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.PipelineElement
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.source.MuseSource
import dev.museframework.engine.api.spi.ConfigBuilder
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration
import dev.museframework.engine.context.MuseContext
import kotlin.reflect.KClass
import dev.museframework.engine.api.spi.MuseComponentFactory as SpiMuseComponentFactory
import dev.museframework.engine.api.spi.MuseContext as SpiMuseContext

typealias ProcessorBuilder<T> = dev.museframework.engine.api.spi.ProcessorBuilder<T>
typealias SourceBuilder<T> = dev.museframework.engine.api.spi.SourceBuilder<T>
typealias RouterBuilder<T> = dev.museframework.engine.api.spi.RouterBuilder<T>

class MuseComponentFactory : SpiMuseComponentFactory {
    private var frozen = false

    internal fun frozenCopy(): MuseComponentFactory = MuseComponentFactory().also {
        it.processorBuilders.putAll(processorBuilders)
        it.routerBuilders.putAll(routerBuilders)
        it.sourceBuilders.putAll(sourceBuilders)
        it.configHandlers.putAll(configHandlers)
        it.frozen = true
    }

    private fun checkRegistrationOpen() { check(!frozen) { "Application component registrations are frozen" } }

    @Suppress("UNCHECKED_CAST")
    internal fun policyDeclaration(component: Router): PolicyRouterDeclaration<Router>? =
        (routerBuilders[component::class] as? RouterRegistration.Managed)?.declaration as? PolicyRouterDeclaration<Router>

    private val processorBuilders = mutableMapOf<KClass<out Processor>, ProcessorBuilder<*>>()
    private sealed interface RouterRegistration {
        class Legacy(val builder: RouterBuilder<*>) : RouterRegistration
        class Managed(val declaration: PolicyRouterDeclaration<*>) : RouterRegistration
    }
    private val routerBuilders = mutableMapOf<KClass<out Router>, RouterRegistration>()
    private val sourceBuilders = mutableMapOf<KClass<out Source>, SourceBuilder<*>>()
    private val configHandlers = mutableMapOf<KClass<*>, (MuseConfiguration, MuseContext) -> Unit>()

    override fun <T : MuseConfiguration> registerConfig(
        type: KClass<T>,
        builder: ConfigBuilder<T>,
    ) {
        checkRegistrationOpen()
        @Suppress("UNCHECKED_CAST")
        configHandlers[type] = { config, context ->
            builder.handle(config as T, context)
        }
    }

    fun applyConfig(
        config: MuseConfiguration,
        context: MuseContext,
    ) {
        val handler =
            configHandlers[config::class]
                ?: return
        handler(config, context)
    }

    override fun <T : Processor> registerProcessor(
        type: KClass<T>,
        builder: ProcessorBuilder<T>,
    ) {
        checkRegistrationOpen()
        processorBuilders[type] = builder
    }

    override fun <T : Router> registerRouter(
        type: KClass<T>,
        builder: RouterBuilder<T>,
    ) {
        checkRegistrationOpen()
        routerBuilders[type] = RouterRegistration.Legacy(builder)
    }

    override fun <T : Router> registerPolicyRouter(type: KClass<T>, declaration: PolicyRouterDeclaration<T>) {
        checkRegistrationOpen()
        routerBuilders[type] = RouterRegistration.Managed(declaration)
    }

    override fun <T : Source> registerSource(
        type: KClass<T>,
        builder: SourceBuilder<T>,
    ) {
        checkRegistrationOpen()
        sourceBuilders[type] = builder
    }

    @Suppress("UNCHECKED_CAST")
    override fun createProcessor(
        component: Processor,
        context: SpiMuseContext,
    ): Any {
        val builder =
            processorBuilders[component::class]
                ?: throw IllegalStateException("No builder registered for ${component::class}")
        val processor = (builder as ProcessorBuilder<Processor>).build(component, context) as MuseProcessor
        return PipelineElement(processor)
    }

    @Suppress("UNCHECKED_CAST")
    override fun createRouter(
        component: Router,
        context: SpiMuseContext,
    ): Any {
        val builder =
            routerBuilders[component::class]
                ?: throw IllegalStateException("No builder registered for ${component::class}")
        return when (builder) {
            is RouterRegistration.Legacy ->
                PipelineElement((builder.builder as RouterBuilder<Router>).build(component, context) as MuseProcessor)
            is RouterRegistration.Managed ->
                checkNotNull((context as? MuseContext)?.policyBindings) {
                    "Managed routers require application bootstrap"
                }.router(component)
        }
    }

    fun <C : Source> createSource(
        component: C,
        context: MuseContext,
        expressionEvaluator: MuseExpressionEvaluator,
    ): MuseSource {
        @Suppress("UNCHECKED_CAST")
        val builder =
            sourceBuilders[component::class] as? SourceBuilder<C>
                ?: throw IllegalStateException("No source builder registered for ${component::class}")
        return builder.build(component, context, expressionEvaluator)
    }
}

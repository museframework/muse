package dev.museframework.engine.api.spi

import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.source.MuseSource
import kotlin.reflect.KClass

interface MuseComponentFactory {
    fun <T : Router> registerPolicyRouter(
        type: KClass<T>,
        declaration: PolicyRouterDeclaration<T>,
    )

    fun <T : MuseConfiguration> registerConfig(
        type: KClass<T>,
        builder: ConfigBuilder<T>,
    )

    fun <T : Processor> registerProcessor(
        type: KClass<T>,
        builder: ProcessorBuilder<T>,
    )

    fun <T : Router> registerRouter(
        type: KClass<T>,
        builder: RouterBuilder<T>,
    )

    fun <T : Source> registerSource(
        type: KClass<T>,
        builder: SourceBuilder<T>,
    )

    fun createProcessor(
        component: Processor,
        context: MuseContext,
    ): Any

    fun createRouter(
        component: Router,
        context: MuseContext,
    ): Any
}

fun interface ConfigBuilder<T : MuseConfiguration> {
    fun handle(
        config: T,
        context: MuseContext,
    )
}

fun interface ProcessorBuilder<T : Processor> {
    fun build(
        component: T,
        context: MuseContext,
    ): Any
}

fun interface SourceBuilder<T : Source> {
    fun build(
        component: T,
        context: MuseContext,
        expressionEvaluator: MuseExpressionEvaluator,
    ): MuseSource
}

fun interface RouterBuilder<T : Router> {
    fun build(
        component: T,
        context: MuseContext,
    ): Any
}

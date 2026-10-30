package dev.museframework.engine.context

import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.engine.api.execution.ResolvedErrorHandler
import dev.museframework.engine.factory.MuseComponentFactory
import java.util.*

class MuseContext(
    override val flows: Map<String, MuseFlow>,
    override val configs: Map<String, MuseConfiguration>,
    globalErrorHandler: ResolvedErrorHandler?,
    val defaultErrorHandler: ResolvedErrorHandler,
    override val factory: MuseComponentFactory,
    override val properties: Properties = Properties(),
    override val expressionEvaluator: MuseExpressionEvaluator,
) : MuseContext {
    var globalErrorHandler: ResolvedErrorHandler? = globalErrorHandler
        internal set
    internal var policyBindings: dev.museframework.engine.execution.policy.PolicyBindings? = null
}

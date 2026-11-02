package dev.museframework.engine.api.spi

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import java.util.Properties

interface MuseContext {
    val properties: Properties
    val resources: ApplicationResources
    val expressionEvaluator: MuseExpressionEvaluator
    val factory: MuseComponentFactory
    val flows: Map<String, MuseFlow>
    val configs: Map<String, MuseConfiguration>
    /** All global declarations in source order; documentation labels need not be unique. */
    val configurationDeclarations: List<MuseConfiguration>
        get() = configs.values.toList()
}

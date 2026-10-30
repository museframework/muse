package dev.museframework.engine.api.execution.policy

import dev.museframework.dsl.api.model.component.Router
import dev.museframework.engine.api.spi.MuseContext

fun interface PolicyRouterDeclaration<T : Router> {
    fun declare(component: T, definition: PolicyDefinition, context: MuseContext): PolicyFactory
}

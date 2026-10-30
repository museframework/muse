package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration

/**
 * Standard Try scope: the declared body runs as a raw subtree, so failures stay local until this
 * owner's own handler decides between recovery and propagation.
 */
internal val tryPolicyDeclaration =
    PolicyRouterDeclaration<TryConfig> { component, definition, _ ->
        val body = definition.inline(component.pipeline)
        component.errorHandler?.let(definition::localHandler)
        PolicyFactory { ExecutionPolicy { invocation -> invocation.call(body) } }
    }

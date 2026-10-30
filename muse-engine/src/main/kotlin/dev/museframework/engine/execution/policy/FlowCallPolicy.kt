package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration

internal class FlowCallPolicy(
    private val body: BodyHandle,
    private val target: TargetConfig?,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome {
        val outcome = invocation.call(body)
        return target?.let { invocation.project(outcome, it, invocation.input) } ?: outcome
    }
}

internal val flowRefPolicyDeclaration =
    PolicyRouterDeclaration<FlowRefConfig> { component, definition, _ ->
        val body = definition.flow(component.ref)
        PolicyFactory { FlowCallPolicy(body, component.targetConfig) }
    }

package dev.museframework.test.policy

import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar

data class DefaultOnFailureConfig(
    val body: List<PipelineElement>,
    val eligibleErrorTypes: Set<ErrorType>,
    val replacement: TypedValue<Any>,
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Router

class DefaultOnFailurePolicy(
    private val body: BodyHandle,
    private val eligible: Set<ErrorType>,
    private val replacement: TypedValue<Any>,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome =
        when (val outcome = invocation.call(body)) {
            is PolicyOutcome.Success -> outcome
            is PolicyOutcome.Failure ->
                if (outcome.error.errorType in eligible) {
                    PolicyOutcome.Success(
                        invocation.input.copy(payload = replacement),
                    )
                } else {
                    outcome
                }
        }
}

class DefaultOnFailureRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerPolicyRouter(
            DefaultOnFailureConfig::class,
            PolicyRouterDeclaration { component, definition, _ ->
                require(component.eligibleErrorTypes.isNotEmpty())
                val body = definition.inline(component.body)
                val eligible = component.eligibleErrorTypes.toSet()
                PolicyFactory {
                    DefaultOnFailurePolicy(body, eligible, component.replacement)
                }
            },
        )
    }
}

fun PipelineOwner.defaultOnFailure(config: DefaultOnFailureConfig) {
    router(config)
}

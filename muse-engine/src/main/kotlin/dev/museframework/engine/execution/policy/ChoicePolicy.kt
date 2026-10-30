package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration

internal class ChoicePolicy(
    private val component: ChoiceConfig,
    private val whenBodies: List<BodyHandle>,
    private val otherwise: BodyHandle?,
    private val expressionEvaluator: MuseExpressionEvaluator,
) : ExecutionPolicy {
    init {
        require(component.whenRoutes.size == whenBodies.size) { "Every choice route must have a declared body" }
    }

    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome {
        component.whenRoutes.forEachIndexed { index, route ->
            val value = try {
                expressionEvaluator.evaluate(route.expression, invocation.input)?.value
            } catch (exception: MuseExpressionException) {
                return expressionFailure(
                    description = "Choice expression evaluation failed",
                    exception = exception,
                    invocation = invocation,
                )
            }
            if (value == true || value == "true") return invocation.call(whenBodies[index])
        }
        return otherwise?.let { invocation.call(it) } ?: PolicyOutcome.Success(invocation.input)
    }

    private fun expressionFailure(
        description: String,
        exception: MuseExpressionException,
        invocation: PolicyInvocation,
    ): PolicyOutcome.Failure {
        val error = MuseError(
            description = description,
            detailedDescription = exception.message ?: "Expression evaluation failed",
            errorType = ErrorType.Mule.EXPRESSION,
            cause = exception,
            sourceFile = component.sourceFile,
            sourceLine = component.sourceLine,
            sourceClass = component.sourceClass,
        )
        return PolicyOutcome.Failure(error, invocation.input.copy(error = error))
    }
}

internal val choicePolicyDeclaration =
    PolicyRouterDeclaration<ChoiceConfig> { component, definition, context ->
        val whenBodies = component.whenRoutes.map { definition.inline(it.pipeline) }
        val otherwise = component.otherwise?.let(definition::inline)
        PolicyFactory { ChoicePolicy(component, whenBodies, otherwise, context.expressionEvaluator) }
    }

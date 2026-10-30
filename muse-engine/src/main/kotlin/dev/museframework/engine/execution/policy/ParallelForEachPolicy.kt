package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration

internal class ParallelForEachPolicy(
    private val component: ParallelForEachConfig,
    private val body: BodyHandle,
    private val expressionEvaluator: MuseExpressionEvaluator,
) : ExecutionPolicy {
    init {
        require(component.timeout == null || component.timeout!! > 0) { "Parallel foreach timeout must be positive" }
        require(component.maxConcurrency == null || component.maxConcurrency!! > 0) { "Parallel foreach concurrency must be positive" }
    }

    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome {
        val entry = invocation.input
        val items = try {
            val expression = component.collection
            val value = if (expression == null) entry.payload?.value else expressionEvaluator.evaluate(expression, entry)?.value
            when (value) {
                is Collection<*> -> value.toList()
                is Array<*> -> value.toList()
                is Iterable<*> -> value.toList()
                else -> listOf(value)
            }
        } catch (exception: MuseExpressionException) {
            val error = MuseError(
                description = "Parallel for each collection evaluation failed",
                detailedDescription = exception.message ?: "Expression evaluation failed",
                errorType = ErrorType.Mule.EXPRESSION,
                cause = exception,
                sourceFile = component.sourceFile,
                sourceLine = component.sourceLine,
                sourceClass = component.sourceClass,
            )
            return PolicyOutcome.Failure(error, entry.copy(error = error))
        }

        val outcomes = invocation.group(component.maxConcurrency) {
            val tasks = items.map { start(body, entry.copy(payload = TypedValue.of(it)), component.timeout) }
            tasks.map { await(it) }
        }
        val failures = linkedMapOf<String, MuseError>()
        val successes = linkedMapOf<String, MuseEvent>()
        outcomes.forEachIndexed { index, outcome ->
            when (outcome) {
                is PolicyOutcome.Failure -> failures[index.toString()] = outcome.error
                is PolicyOutcome.Success -> successes[index.toString()] = outcome.event
            }
        }
        if (failures.isNotEmpty()) {
            val error = MuseError(
                description = "One or more Parallel For Each iterations failed",
                detailedDescription = "Iteration failures: ${failures.keys.joinToString(", ")}",
                errorType = ErrorType.Mule.COMPOSITE_ROUTING,
                cause = RuntimeException("Parallel For Each composite routing error"),
                errorMessage = mapOf("failures" to failures, "results" to successes),
                childErrors = failures.values.toList(),
            )
            return PolicyOutcome.Failure(error, entry.copy(error = error))
        }
        val success = PolicyOutcome.Success(entry.copy(payload = TypedValue.of(outcomes.map { it.event })))
        return component.targetConfig?.let { invocation.project(success, it, entry) } ?: success
    }
}

internal val parallelForEachPolicyDeclaration =
    PolicyRouterDeclaration<ParallelForEachConfig> { component, definition, context ->
        val body = definition.inline(component.pipeline)
        PolicyFactory { ParallelForEachPolicy(component, body, context.expressionEvaluator) }
    }

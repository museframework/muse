package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration

internal class ScatterGatherPolicy(
    private val component: ScatterGatherConfig,
    private val routes: List<BodyHandle>,
) : ExecutionPolicy {
    init {
        require(component.timeout == null || component.timeout!! > 0) { "Scatter timeout must be positive" }
        require(component.maxConcurrency == null || component.maxConcurrency!! > 0) { "Scatter concurrency must be positive" }
    }

    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome = invocation.group(component.maxConcurrency) {
        val entry = invocation.input
        val tasks = routes.map { start(it, entry, component.timeout) }
        val remaining = tasks.toMutableList()
        val outcomes = arrayOfNulls<PolicyOutcome>(tasks.size)
        while (remaining.isNotEmpty()) {
            val completion = awaitAny(remaining)
            remaining.remove(completion.task)
            if ((this as ManagedPolicyGroup).timerExpired(completion.task)) {
                cancelAndJoin(remaining)
                val failure = completion.outcome as PolicyOutcome.Failure
                return@group PolicyOutcome.Failure(failure.error, entry.copy(error = failure.error))
            }
            outcomes[tasks.indexOf(completion.task)] = completion.outcome
        }

        val failures = linkedMapOf<String, MuseError>()
        val successes = linkedMapOf<String, MuseEvent>()
        outcomes.forEachIndexed { index, outcome ->
            when (outcome) {
                is PolicyOutcome.Failure -> failures[index.toString()] = outcome.error
                is PolicyOutcome.Success -> successes[index.toString()] = outcome.event
                null -> error("Scatter route was not observed")
            }
        }
        if (failures.isNotEmpty()) {
            val error = MuseError(
                description = "One or more scatter-gather routes failed",
                detailedDescription = "Route failures: ${failures.keys.joinToString(", ")}",
                errorType = ErrorType.Mule.COMPOSITE_ROUTING,
                cause = RuntimeException("Scatter-Gather composite routing error"),
                errorMessage = mapOf("failures" to failures, "results" to successes),
                childErrors = failures.values.toList(),
            )
            return@group PolicyOutcome.Failure(error, entry.copy(error = error))
        }

        val events = successes.values.toList()
        val merged = entry.variables.toMutableMap()
        val changes = events.map { event -> event.variables.filter { (key, value) -> entry.variables[key] != value } }
        for (name in changes.flatMap { it.keys }.toSet()) {
            val values = changes.mapNotNull { it[name] }
            merged[name] = if (values.size == 1) values.single() else TypedValue.of(values.map { it.value })
        }
        val base = entry.copy(variables = merged)
        val success = PolicyOutcome.Success(base.copy(payload = TypedValue.of(events)))
        component.targetConfig?.let { invocation.project(success, it, base) } ?: success
    }
}

internal val scatterGatherPolicyDeclaration =
    PolicyRouterDeclaration<ScatterGatherConfig> { component, definition, _ ->
        val routes = component.routes.map { definition.inline(it.pipeline) }
        PolicyFactory { ScatterGatherPolicy(component, routes) }
    }

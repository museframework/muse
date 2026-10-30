package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.ExecutionPolicy
import dev.museframework.engine.api.execution.policy.PolicyInvocation
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.EvaluationRequest
import dev.museframework.engine.api.expression.IterationSource
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.engine.api.execution.policy.PolicyFactory
import dev.museframework.engine.api.execution.policy.PolicyRouterDeclaration
import kotlinx.coroutines.CancellationException
import java.util.Properties

internal class ForEachPolicy(
    private val component: ForEachConfig,
    private val body: BodyHandle,
    private val expressionEvaluator: MuseExpressionEvaluator,
) : ExecutionPolicy {
    override suspend fun execute(invocation: PolicyInvocation): PolicyOutcome {
        val original = invocation.input
        val source = try {
            expressionEvaluator.split(
                EvaluationRequest.Iteration(component.collection?.let(IterationSource::Expression) ?: IterationSource.EventPayload),
                original,
            )
        } catch (exception: MuseExpressionException) {
            return collectionFailure(exception, original)
        }
        val batchSize = component.batchSize
        var current = original
        var result: PolicyOutcome? = null
        var primaryFailure: Throwable? = null
        try {
            var index = 0
            while (true) {
                val batch = try {
                    nextBatch(source, batchSize)
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    return collectionFailure(exception, current).also { result = it }
                } ?: break
                val itemEvent = original.copy(
                    payload = batch.value,
                    variables = current.variables + mapOf(
                        component.counterVariableName to TypedValue.of(++index),
                        component.rootMessageVariableName to TypedValue.of(original),
                    ),
                )
                when (val outcome = invocation.call(body, itemEvent)) {
                    is PolicyOutcome.Failure -> return outcome.also { result = it }
                    is PolicyOutcome.Success -> current = current.copy(variables = outcome.event.variables)
                }
            }
            return PolicyOutcome.Success(current.copy(payload = original.payload, attributes = original.attributes))
                .also { result = it }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                source.close()
            } catch (cleanup: Throwable) {
                val primary = primaryFailure ?: (result as? PolicyOutcome.Failure)?.error?.cause
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    private fun nextBatch(iterator: Iterator<TypedValue<Any>>, batchSize: Int): Batch? {
        if (!iterator.hasNext()) return null
        if (batchSize == 1) return Batch(iterator.next())
        val items = ArrayList<Any?>()
        items += iterator.next().value
        while (items.size < batchSize && iterator.hasNext()) items += iterator.next().value
        return Batch(TypedValue.of(items))
    }

    private fun collectionFailure(exception: Exception, event: dev.museframework.engine.api.event.MuseEvent): PolicyOutcome.Failure {
        val error = MuseError(
            description = "For each collection evaluation failed",
            detailedDescription = exception.message ?: "Expression evaluation failed",
            errorType = ErrorType.Mule.EXPRESSION,
            cause = exception,
            sourceFile = component.sourceFile,
            sourceLine = component.sourceLine,
            sourceClass = component.sourceClass,
        )
        return PolicyOutcome.Failure(error, event.copy(error = error))
    }

    private data class Batch(val value: TypedValue<Any>)
}

internal val forEachPolicyDeclaration =
    PolicyRouterDeclaration<ForEachConfig> { component, definition, context ->
        val resolved = component.copy(
            batchSize = resolveForEachBatchSize(component, context.properties),
            batchSizeExpression = null,
        )
        val body = definition.inline(component.pipeline)
        PolicyFactory { ForEachPolicy(resolved, body, context.expressionEvaluator) }
    }

internal fun resolveForEachBatchSize(component: ForEachConfig, properties: Properties): Int {
    val size = component.batchSizeExpression?.let { expression ->
        val resolved = Regex("""\$\{([^}]+)}""").replace(expression) { match ->
            properties.getProperty(match.groupValues[1])
                ?: throw IllegalArgumentException("For each batchSize references missing property '${match.groupValues[1]}'")
        }
        resolved.toIntOrNull() ?: throw IllegalArgumentException("For each batchSize must resolve to a positive integer")
    } ?: component.batchSize
    require(size > 0) { "For each batchSize must be greater than zero" }
    return size
}

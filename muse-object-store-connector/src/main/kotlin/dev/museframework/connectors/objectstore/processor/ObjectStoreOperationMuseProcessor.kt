package dev.museframework.connectors.objectstore.processor

import dev.museframework.connectors.objectstore.backend.ObjectStoreBackend
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackendCorruptionException
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackendKeyAlreadyExistsException
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackendKeyNotFoundException
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackendValueValidationException
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.projectTarget
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.objectstore.api.config.*
import dev.museframework.objectstore.api.model.ObjectStoreErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

internal class ObjectStoreOperationMuseProcessor(
    override val component: ObjectStoreOperationConfig,
    private val expressionEvaluator: MuseExpressionEvaluator,
    private val resolveStore: (ObjectStoreRef) -> ObjectStoreBackend,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult =
        try {
            val store = resolveStore(component.objectStore)
            withContext(Dispatchers.IO) {
                when (val operation = component) {
                    is ObjectStoreClearConfig -> {
                        store.withLock { store.clear() }
                        ProcessorResult.Success(context)
                    }
                    is ObjectStoreContainsConfig ->
                        result(context, TypedValue.of(store.withLock { store.contains(key(operation.key, context)) } as Any), operation.targetConfig)
                    is ObjectStoreRemoveConfig -> {
                        val key = key(operation.key, context)
                        store.withLock { store.remove(key) }
                        ProcessorResult.Success(context)
                    }
                    is ObjectStoreRetrieveConfig -> {
                        val key = key(operation.key, context)
                        val value = store.withLock { store.retrieve(key) }
                            ?: operation.defaultValue?.let { expressionEvaluator.evaluate(it, context.event) }
                        if (value?.value == null) throw OperationFailure(ObjectStoreErrorType.KEY_NOT_FOUND, "Object Store key not found: $key")
                        @Suppress("UNCHECKED_CAST")
                        result(context, value as TypedValue<Any>, operation.targetConfig)
                    }
                    is ObjectStoreRetrieveAllConfig -> {
                        val values = store.withLock { store.retrieveAll() }
                        val snapshot = Collections.unmodifiableMap(values.mapValues { it.value.value })
                        result(context, TypedValue.of(snapshot as Any), operation.targetConfig)
                    }
                    is ObjectStoreRetrieveAllKeysConfig ->
                        result(context, TypedValue.of(store.withLock { store.retrieveAllKeys() } as Any), operation.targetConfig)
                    is ObjectStoreStoreConfig -> {
                        val key = key(operation.key, context)
                        val value = operation.value?.let { expressionEvaluator.evaluate(it, context.event) } ?: if (operation.value == null) context.event.payload else null
                        if (value?.value == null) {
                            if (operation.failOnNullValue) throw OperationFailure(ObjectStoreErrorType.NULL_VALUE, "Object Store value is null")
                        } else {
                            val snapshot = store.snapshot(value)
                            store.withLock { store.putSnapshot(key, snapshot, operation.failIfPresent) }
                        }
                        ProcessorResult.Success(context)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure(context, failure)
        }

    private fun key(expression: String, context: ExecutionContext): String {
        val value = expressionEvaluator.evaluate(expression, context.event)?.value?.toString()
        if (value.isNullOrEmpty()) throw OperationFailure(ObjectStoreErrorType.INVALID_KEY, "Object Store key is null or empty")
        return value
    }

    private fun result(base: ExecutionContext, value: TypedValue<Any>, target: TargetConfig?): ProcessorResult {
        val updated = base.withEvent(base.event.copy(payload = value))
        if (target == null) return ProcessorResult.Success(updated)
        return when (val projected = updated.projectTarget(PolicyOutcome.Success(updated.event), target, base.event)) {
            is PolicyOutcome.Success -> ProcessorResult.Success(updated.withEvent(projected.event))
            is PolicyOutcome.Failure -> ProcessorResult.Failure(projected.error, updated.withEvent(projected.event))
        }
    }

    private fun failure(context: ExecutionContext, cause: Exception): ProcessorResult.Failure {
        val type = when (cause) {
            is OperationFailure -> cause.type
            is ObjectStoreBackendKeyAlreadyExistsException -> ObjectStoreErrorType.KEY_ALREADY_EXISTS
            is ObjectStoreBackendKeyNotFoundException -> ObjectStoreErrorType.KEY_NOT_FOUND
            is ObjectStoreBackendValueValidationException -> ErrorType.Mule.VALIDATION
            is MuseExpressionException -> ErrorType.Mule.EXPRESSION
            is ObjectStoreBackendCorruptionException -> ObjectStoreErrorType.STORE_NOT_AVAILABLE
            else -> ObjectStoreErrorType.STORE_NOT_AVAILABLE
        }
        val error = MuseError(
            description = "Object Store operation failed",
            detailedDescription = cause.message ?: cause::class.simpleName.orEmpty(),
            errorType = type,
            cause = cause,
            sourceFile = component.sourceFile,
            sourceLine = component.sourceLine,
            sourceClass = component.sourceClass,
        )
        return ProcessorResult.Failure(error, context.withError(error))
    }

    private class OperationFailure(val type: ErrorType, message: String) : IllegalArgumentException(message)
}

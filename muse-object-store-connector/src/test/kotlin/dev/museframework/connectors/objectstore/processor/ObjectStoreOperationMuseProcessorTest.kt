package dev.museframework.connectors.objectstore.processor

import dev.museframework.connectors.objectstore.internal.H2ObjectStore
import dev.museframework.connectors.objectstore.backend.ObjectStoreBackend
import dev.museframework.connectors.objectstore.backend.TestObjectStoreBackend
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import dev.museframework.objectstore.api.config.*
import dev.museframework.objectstore.api.model.ObjectStoreErrorType
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ObjectStoreOperationMuseProcessorTest {
    private val evaluations = mutableListOf<String>()
    private val evaluator =
        object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? {
                evaluations += expression
                return when (expression) {
                    "#[payload]" -> event.payload
                    "#[null]" -> TypedValue.empty()
                    "#[missing]" -> null
                    "#[empty]" -> TypedValue.of("")
                    "#[explode]" -> throw MuseExpressionException("expression failed")
                    "#[unsupported]" -> TypedValue.of(Any())
                    else -> TypedValue.of(expression)
                }
            }
        }

    private fun store(): H2ObjectStore =
        H2ObjectStore(ObjectStoreConfig("test", persistent = false), "jdbc:h2:mem:${UUID.randomUUID()}")

    private fun processor(component: ObjectStoreOperationConfig, store: ObjectStoreBackend) =
        ObjectStoreOperationMuseProcessor(component, evaluator) { store }

    private fun context(event: MuseEvent = MuseEvent()): ExecutionContext =
        ExecutionContext(event = event, errorHandler = null, coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), expressionEvaluator = evaluator)

    private suspend fun execute(component: ObjectStoreOperationConfig, store: ObjectStoreBackend, event: MuseEvent = MuseEvent()): ProcessorResult =
        processor(component, store).process(context(event))

    @Test
    fun `seven operations preserve void events and return stable value snapshots`() = runTest {
        store().use { store ->
            val input = MuseEvent(payload = TypedValue.of("payload" as Any))
            assertSame(input, assertIs<ProcessorResult.Success>(execute(ObjectStoreStoreConfig("z"), store, input)).context.event)
            assertSame(input, assertIs<ProcessorResult.Success>(execute(ObjectStoreStoreConfig("a", "first"), store, input)).context.event)

            assertEquals(true, assertIs<ProcessorResult.Success>(execute(ObjectStoreContainsConfig("z"), store)).context.event.payload?.value)
            assertEquals("payload", assertIs<ProcessorResult.Success>(execute(ObjectStoreRetrieveConfig("z"), store)).context.event.payload?.value)
            val all = assertIs<Map<*, *>>(assertIs<ProcessorResult.Success>(execute(ObjectStoreRetrieveAllConfig(), store)).context.event.payload?.value)
            assertEquals(listOf("a", "z"), all.keys.toList())
            assertEquals("first", all["a"])
            assertEquals(listOf("a", "z"), assertIs<ProcessorResult.Success>(execute(ObjectStoreRetrieveAllKeysConfig(), store)).context.event.payload?.value)

            assertSame(input, assertIs<ProcessorResult.Success>(execute(ObjectStoreRemoveConfig("a"), store, input)).context.event)
            assertSame(input, assertIs<ProcessorResult.Success>(execute(ObjectStoreClearConfig(), store, input)).context.event)
            assertEquals(false, assertIs<ProcessorResult.Success>(execute(ObjectStoreContainsConfig("z"), store)).context.event.payload?.value)
        }
    }

    @Test
    fun `read target keeps original payload and projects result into a variable`() = runTest {
        store().use { store ->
            store.put("key", TypedValue.of("stored"), false)
            val input = MuseEvent(payload = TypedValue.of("original" as Any))
            val result = assertIs<ProcessorResult.Success>(execute(ObjectStoreRetrieveConfig("key", targetConfig = TargetConfig("found")), store, input))

            assertEquals("original", result.context.event.payload?.value)
            assertEquals("stored", result.context.event.variables["found"]?.value)
            assertEquals(input.correlationId, result.context.event.correlationId)
        }
    }

    @Test
    fun `retrieve default is evaluated only when key is absent and is never stored`() = runTest {
        store().use { store ->
            store.put("present", TypedValue.of("saved"), false)
            evaluations.clear()
            assertEquals("saved", assertIs<ProcessorResult.Success>(execute(ObjectStoreRetrieveConfig("present", defaultValue = "fallback"), store)).context.event.payload?.value)
            assertFalse("fallback" in evaluations)
            assertEquals("fallback", assertIs<ProcessorResult.Success>(execute(ObjectStoreRetrieveConfig("absent", defaultValue = "fallback"), store)).context.event.payload?.value)
            assertFalse(store.contains("absent"))
        }
    }

    @Test
    fun `null or empty evaluated keys return INVALID_KEY without literal fallback`() = runTest {
        store().use { store ->
            listOf("#[null]", "#[missing]", "#[empty]").forEach { key ->
                val failure = assertIs<ProcessorResult.Failure>(execute(ObjectStoreStoreConfig(key), store, MuseEvent(payload = TypedValue.of("value" as Any))))
                assertEquals(ObjectStoreErrorType.INVALID_KEY, failure.error.errorType)
                assertFalse(store.contains(key))
            }
        }
    }

    @Test
    fun `duplicate missing and null value flags map to documented OS errors`() = runTest {
        store().use { store ->
            val event = MuseEvent(payload = TypedValue.of("first" as Any))
            assertIs<ProcessorResult.Success>(execute(ObjectStoreStoreConfig("key"), store, event))
            assertEquals(ObjectStoreErrorType.KEY_ALREADY_EXISTS, assertIs<ProcessorResult.Failure>(execute(ObjectStoreStoreConfig("key", "second", failIfPresent = true), store)).error.errorType)
            assertEquals("first", store.retrieve("key")?.value)
            assertEquals(ObjectStoreErrorType.KEY_NOT_FOUND, assertIs<ProcessorResult.Failure>(execute(ObjectStoreRemoveConfig("missing"), store)).error.errorType)
            assertEquals(ObjectStoreErrorType.KEY_NOT_FOUND, assertIs<ProcessorResult.Failure>(execute(ObjectStoreRetrieveConfig("missing", defaultValue = "#[null]"), store)).error.errorType)
            assertEquals(ObjectStoreErrorType.NULL_VALUE, assertIs<ProcessorResult.Failure>(execute(ObjectStoreStoreConfig("null", "#[null]"), store)).error.errorType)
            assertIs<ProcessorResult.Success>(execute(ObjectStoreStoreConfig("null", "#[null]", failOnNullValue = false), store))
            assertFalse(store.contains("null"))
        }
    }

    @Test
    fun `external provider key failures retain connector error types`() = runTest {
        TestObjectStoreBackend("selected", null).use { store ->
            val missing = assertIs<ProcessorResult.Failure>(execute(ObjectStoreRemoveConfig("missing"), store))
            assertEquals(ObjectStoreErrorType.KEY_NOT_FOUND, missing.error.errorType)

            assertIs<ProcessorResult.Success>(execute(ObjectStoreStoreConfig("key", "first"), store))
            val duplicate = assertIs<ProcessorResult.Failure>(execute(ObjectStoreStoreConfig("key", "second", failIfPresent = true), store))
            assertEquals(ObjectStoreErrorType.KEY_ALREADY_EXISTS, duplicate.error.errorType)
            assertEquals("first", store.retrieve("key")?.value)
        }
    }

    @Test
    fun `value validation and expression failures preserve their distinct Mule error types`() = runTest {
        store().use { store ->
            store.put("key", TypedValue.of("original"), false)
            val invalidValue = assertIs<ProcessorResult.Failure>(execute(ObjectStoreStoreConfig("key", "#[unsupported]"), store))
            assertEquals(ErrorType.Mule.VALIDATION, invalidValue.error.errorType)
            assertEquals("original", store.retrieve("key")?.value)

            val invalidExpression = assertIs<ProcessorResult.Failure>(execute(ObjectStoreStoreConfig("key", "#[explode]"), store))
            assertEquals(ErrorType.Mule.EXPRESSION, invalidExpression.error.errorType)
            assertIs<MuseExpressionException>(invalidExpression.error.cause)
        }
    }

    @Test
    fun `cancellation during value snapshot cannot commit the store operation`() = runBlocking {
        store().use { store ->
            val enteredSnapshot = CountDownLatch(1)
            val releaseSnapshot = CountDownLatch(1)
            val slowValue = object : AbstractList<Any>() {
                override val size: Int = 1
                override fun get(index: Int): Any {
                    enteredSnapshot.countDown()
                    check(releaseSnapshot.await(5, TimeUnit.SECONDS)) { "snapshot was never released" }
                    return "item"
                }
            }
            val slowEvaluator = object : MuseExpressionEvaluator {
                override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any> =
                    if (expression == "#[slow]") TypedValue.of(slowValue as Any) else TypedValue.of(expression as Any)
            }
            val operation = ObjectStoreOperationMuseProcessor(ObjectStoreStoreConfig("key", "#[slow]"), slowEvaluator) { store }
            val job = launch(Dispatchers.IO) { operation.process(context().copy(expressionEvaluator = slowEvaluator)) }

            try {
                assertTrue(enteredSnapshot.await(5, TimeUnit.SECONDS))
                job.cancel()
            } finally {
                releaseSnapshot.countDown()
            }
            job.join()
            assertFalse(store.contains("key"))
        }
    }
}

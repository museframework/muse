package dev.museframework.connectors.db.processor

import dev.museframework.connectors.db.connection.DbConnectionFactory
import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.config.GenericDbConnectionConfig
import dev.museframework.db.api.operation.DbQuerySingleConfig
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.expression.MuseExpressionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class DbOperationTargetProjectionTest {
    private val dbConfig = DbConfig(
        name = "target-test",
        connection = GenericDbConnectionConfig(
            url = "jdbc:h2:mem:db-target;DB_CLOSE_DELAY=-1",
            driverClassName = "org.h2.Driver",
            user = "sa",
            password = "",
        ),
    )

    @Test
    fun `target value reads query result while preserving input event`() = runBlocking {
        val original = inputEvent()
        val evaluator = evaluator { event ->
            val row = assertIs<Map<*, *>>(event.payload?.getOrNull())
            TypedValue.of((row["N"] as Number).toInt() as Any)
        }
        val context = context(original, evaluator, this)
        val result = assertIs<ProcessorResult.Success>(
            processor(TargetConfig("captured", "#[payload.N]"), evaluator).process(context),
        )

        assertEquals(original.payload, result.context.event.payload)
        assertEquals(original.attributes, result.context.event.attributes)
        assertEquals(original.variables["existing"], result.context.event.variables["existing"])
        assertEquals(7, result.context.event.variables["captured"]?.getOrNull())
    }

    @Test
    fun `target without expression stores query payload and preserves input`() = runBlocking {
        val original = inputEvent()
        val evaluator = evaluator { error("No target value expression should be evaluated") }
        val result = assertIs<ProcessorResult.Success>(
            processor(TargetConfig("captured"), evaluator).process(context(original, evaluator, this)),
        )

        assertEquals(original.payload, result.context.event.payload)
        assertEquals(original.attributes, result.context.event.attributes)
        val captured = assertIs<Map<*, *>>(result.context.event.variables["captured"]?.getOrNull())
        assertEquals(7, (captured["N"] as Number).toInt())
    }

    @Test
    fun `target expression failure returns expression error on input event`() = runBlocking {
        val original = inputEvent()
        val defect = MuseExpressionException("invalid target")
        val evaluator = evaluator { throw defect }
        val result = assertIs<ProcessorResult.Failure>(
            processor(TargetConfig("captured", "#[invalid]"), evaluator).process(context(original, evaluator, this)),
        )

        assertEquals(ErrorType.Mule.EXPRESSION, result.error.errorType)
        assertSame(defect, result.error.cause)
        assertEquals(original.copy(error = result.error), result.context.event)
    }

    @Test
    fun `unexpected target evaluator defect is not classified as database failure`() = runBlocking {
        val defect = IllegalStateException("evaluator defect")
        val evaluator = evaluator { throw defect }
        val context = context(inputEvent(), evaluator, this)

        assertSame(defect, assertFailsWith<IllegalStateException> {
            processor(TargetConfig("captured", "#[invalid]"), evaluator).process(context)
        })
    }

    private fun processor(target: TargetConfig?, evaluator: MuseExpressionEvaluator) =
        DbOperationMuseProcessor(
            component = DbQuerySingleConfig(
                configRef = DbConfigRef.ByReference(dbConfig),
                sql = "SELECT 7 AS N",
                targetConfig = target,
            ),
            connectionFactory = DbConnectionFactory(dbConfig, Properties()),
            expressionEvaluator = evaluator,
        )

    private fun context(event: MuseEvent, evaluator: MuseExpressionEvaluator, scope: CoroutineScope) =
        ExecutionContext(event = event, errorHandler = null, coroutineScope = scope, expressionEvaluator = evaluator)

    private fun inputEvent() = MuseEvent(
        payload = TypedValue.of("input" as Any),
        attributes = TypedValue.of("original attributes" as Any),
        variables = mapOf("existing" to TypedValue.of("kept" as Any)),
    )

    private fun evaluator(evaluateTarget: (MuseEvent) -> TypedValue<Any>?) = object : MuseExpressionEvaluator {
        override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? =
            if (expression.startsWith("#[")) evaluateTarget(event) else TypedValue.of(expression as Any)
    }
}

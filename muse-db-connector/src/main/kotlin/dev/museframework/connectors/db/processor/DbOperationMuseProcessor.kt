package dev.museframework.connectors.db.processor

import dev.museframework.connectors.db.DbDriverLoadingException
import dev.museframework.connectors.db.DbParameterException
import dev.museframework.connectors.db.DbScriptNotFoundException
import dev.museframework.connectors.db.connection.DbConnectionFactory
import dev.museframework.connectors.db.execution.JdbcOperations
import dev.museframework.db.api.model.DbErrorType
import dev.museframework.db.api.operation.DbBulkDeleteConfig
import dev.museframework.db.api.operation.DbBulkInsertConfig
import dev.museframework.db.api.operation.DbBulkUpdateConfig
import dev.museframework.db.api.operation.DbDeleteConfig
import dev.museframework.db.api.operation.DbExecuteDdlConfig
import dev.museframework.db.api.operation.DbExecuteScriptConfig
import dev.museframework.db.api.operation.DbInsertConfig
import dev.museframework.db.api.operation.DbOperationConfig
import dev.museframework.db.api.operation.DbQuerySingleConfig
import dev.museframework.db.api.operation.DbSelectConfig
import dev.museframework.db.api.operation.DbStoredProcedureConfig
import dev.museframework.db.api.operation.DbUpdateConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.projectTarget
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.streaming.MuseCursorIteratorProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTransientConnectionException

internal class DbOperationMuseProcessor(
    override val component: DbOperationConfig,
    connectionFactory: DbConnectionFactory,
    expressionEvaluator: MuseExpressionEvaluator,
) : MuseProcessor {
    private val operations = JdbcOperations(connectionFactory, expressionEvaluator)
    private val logger = LoggerFactory.getLogger(DbOperationMuseProcessor::class.java)

    override suspend fun process(context: ExecutionContext): ProcessorResult {
        var unregisteredProvider: MuseCursorIteratorProvider<*>? = null
        val response =
            try {
                val output =
                    withContext(Dispatchers.IO) {
                        when (val operation = component) {
                            is DbSelectConfig ->
                                operations.select(operation, context.event).also { unregisteredProvider = it }
                            is DbQuerySingleConfig -> operations.querySingle(operation, context.event)
                            is DbInsertConfig -> operations.insert(operation, context.event)
                            is DbUpdateConfig -> operations.update(operation, context.event)
                            is DbDeleteConfig -> operations.delete(operation, context.event)
                            is DbBulkInsertConfig -> operations.bulk(operation, context.event)
                            is DbBulkUpdateConfig -> operations.bulk(operation, context.event)
                            is DbBulkDeleteConfig -> operations.bulk(operation, context.event)
                            is DbExecuteDdlConfig -> operations.executeDdl(operation, context.event)
                            is DbExecuteScriptConfig -> operations.executeScript(operation, context.event)
                            is DbStoredProcedureConfig -> operations.storedProcedure(operation, context.event)
                        }
                    }

                if (output is MuseCursorIteratorProvider<*>) {
                    context.streamingResources.register(output)
                    unregisteredProvider = null
                }
                val payload = TypedValue.of(output)
                val resultContext = context.withEvent(context.event.copy(payload = payload))
                ProcessorResult.Success(resultContext)
            } catch (failure: CancellationException) {
                unregisteredProvider?.runCatching { releaseResources() }
                throw failure
            } catch (failure: Throwable) {
                logger.error("Database operation '{}' failed", component.docName.ifBlank { component::class.simpleName }, failure)
                val error = failure.toMuseError()
                ProcessorResult.Failure(error, context.withError(error))
            }
        val success =
            when (response) {
                is ProcessorResult.Success -> response
                is ProcessorResult.Failure -> return response
            }
        val target = component.targetConfig ?: return success
        val projected = context.projectTarget(PolicyOutcome.Success(success.context.event), target, context.event)
        return when (projected) {
            is PolicyOutcome.Success -> ProcessorResult.Success(context.withEvent(projected.event))
            is PolicyOutcome.Failure ->
                ProcessorResult.Failure(
                    projected.error,
                    context.withEvent(projected.event.copy(error = projected.error)),
                )
        }
    }

    private fun Throwable.toMuseError(): MuseError {
        val errorType = classifyError(this)
        val operationName = component.docName.ifBlank { component::class.simpleName ?: "database operation" }
        return MuseError(
            description = message ?: "Database operation failed",
            detailedDescription = "$operationName failed with ${errorType.namespace}:${errorType.identifier}",
            errorType = errorType,
            cause = this,
            errorMessage = message,
            sourceFile = component.sourceFile,
            sourceLine = component.sourceLine,
            sourceClass = component.sourceClass,
        )
    }

    private fun classifyError(failure: Throwable): ErrorType =
        when (failure) {
            is DbDriverLoadingException -> DbErrorType.CANNOT_LOAD_DRIVER
            is DbParameterException, is IllegalArgumentException, is UnsupportedOperationException -> DbErrorType.INVALID_PARAMETER
            is DbScriptNotFoundException -> DbErrorType.SCRIPT_NOT_FOUND
            is SQLSyntaxErrorException -> DbErrorType.BAD_SQL_SYNTAX
            is SQLTransientConnectionException,
            is SQLNonTransientConnectionException,
            is SQLRecoverableException,
            -> DbErrorType.CONNECTIVITY
            is SQLException ->
                when {
                    failure.sqlState?.startsWith("08") == true -> DbErrorType.CONNECTIVITY
                    failure.sqlState?.startsWith("42") == true -> DbErrorType.BAD_SQL_SYNTAX
                    else -> DbErrorType.QUERY_EXECUTION
                }
            else -> DbErrorType.QUERY_EXECUTION
        }
}

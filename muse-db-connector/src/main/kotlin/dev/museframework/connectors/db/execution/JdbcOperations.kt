package dev.museframework.connectors.db.execution

import dev.museframework.connectors.db.DbParameterException
import dev.museframework.connectors.db.DbScriptNotFoundException
import dev.museframework.connectors.db.connection.DbConnectionFactory
import dev.museframework.connectors.db.sql.DbParameterResolver
import dev.museframework.connectors.db.sql.NamedParameterSql
import dev.museframework.connectors.db.sql.ParsedSql
import dev.museframework.connectors.db.sql.SqlScriptSplitter
import dev.museframework.connectors.db.sql.applySettings
import dev.museframework.connectors.db.sql.bind
import dev.museframework.connectors.db.sql.createPreparedStatement
import dev.museframework.connectors.db.sql.materializeJdbcValue
import dev.museframework.connectors.db.sql.readAllRows
import dev.museframework.connectors.db.sql.readCurrentRow
import dev.museframework.connectors.db.sql.readGeneratedKeys
import dev.museframework.connectors.db.sql.registerOutput
import dev.museframework.connectors.db.sql.setJdbcValue
import dev.museframework.connectors.db.streaming.JdbcRowCursorProvider
import dev.museframework.connectors.db.streaming.JdbcRowSource
import dev.museframework.db.api.model.DbGeneratedKeys
import dev.museframework.db.api.model.DbParameterType
import dev.museframework.db.api.model.DbQuerySettings
import dev.museframework.db.api.model.DbStatementResult
import dev.museframework.db.api.model.DbStoredProcedureResult
import dev.museframework.db.api.operation.DbBulkOperationConfig
import dev.museframework.db.api.operation.DbDeleteConfig
import dev.museframework.db.api.operation.DbExecuteDdlConfig
import dev.museframework.db.api.operation.DbExecuteScriptConfig
import dev.museframework.db.api.operation.DbInsertConfig
import dev.museframework.db.api.operation.DbParameterizedOperationConfig
import dev.museframework.db.api.operation.DbQuerySingleConfig
import dev.museframework.db.api.operation.DbSelectConfig
import dev.museframework.db.api.operation.DbStoredProcedureConfig
import dev.museframework.db.api.operation.DbUpdateConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.streaming.MuseCursorIteratorProvider
import dev.museframework.engine.api.streaming.StreamingProviders
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

internal class JdbcOperations(
    private val connectionFactory: DbConnectionFactory,
    private val expressionEvaluator: MuseExpressionEvaluator,
) {
    private val parameterResolver = DbParameterResolver(expressionEvaluator)

    fun select(
        config: DbSelectConfig,
        event: MuseEvent,
    ): MuseCursorIteratorProvider<Map<String, Any?>> {
        JdbcRowCursorProvider.validateStrategy(config.streamingStrategy)
        val sql = resolveText(config.sql, event, "SQL")
        val parsedSql = NamedParameterSql.parse(sql)
        val parameters = parameterResolver.resolve(config.inputParameters, event)
        var connection: Connection? = null
        var statement: PreparedStatement? = null
        var resultSet: ResultSet? = null
        try {
            connection = connectionFactory.open()
            statement = connection.prepareStatement(parsedSql.jdbcSql)
            statement.applySettings(config.settings)
            statement.bind(parsedSql, parameters, config.parameterTypes)
            resultSet = statement.executeQuery()
            val source = JdbcRowSource(connection, statement, resultSet, config.settings.useColumnNumbers)
            connection = null
            statement = null
            resultSet = null
            return try {
                StreamingProviders.managed(JdbcRowCursorProvider(source, config.streamingStrategy))
            } catch (failure: Throwable) {
                runCatching(source::close)
                throw failure
            }
        } catch (failure: Throwable) {
            listOfNotNull<AutoCloseable>(resultSet, statement, connection).forEach { resource ->
                try {
                    resource.close()
                } catch (closeFailure: Throwable) {
                    if (closeFailure !== failure) failure.addSuppressed(closeFailure)
                }
            }
            throw failure
        }
    }

    fun querySingle(
        config: DbQuerySingleConfig,
        event: MuseEvent,
    ): Map<String, Any?> =
        connectionFactory.open().use { connection ->
            prepare(connection, config, event).use { statement ->
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.readCurrentRow(config.settings.useColumnNumbers) else emptyMap()
                }
            }
        }

    fun insert(
        config: DbInsertConfig,
        event: MuseEvent,
    ): DbStatementResult = executeDml(config, config.generatedKeys, event)

    fun update(
        config: DbUpdateConfig,
        event: MuseEvent,
    ): DbStatementResult = executeDml(config, config.generatedKeys, event)

    fun delete(
        config: DbDeleteConfig,
        event: MuseEvent,
    ): Int =
        connectionFactory.open().use { connection ->
            prepare(connection, config, event).use(PreparedStatement::executeUpdate)
        }

    fun bulk(
        config: DbBulkOperationConfig,
        event: MuseEvent,
    ): List<Int> {
        val sql = resolveText(config.sql, event, "SQL")
        val parsedSql = NamedParameterSql.parse(sql)
        val batch = parameterResolver.resolveBatch(config.batchParameters, event)
        if (batch.isEmpty()) return emptyList()

        return connectionFactory.open().use { connection ->
            localTransaction(connection) {
                connection.prepareStatement(parsedSql.jdbcSql).use { statement ->
                    statement.applySettings(config.settings)
                    batch.forEach { parameters ->
                        statement.bind(parsedSql, parameters, config.parameterTypes)
                        statement.addBatch()
                    }
                    statement.executeBatch().toList()
                }
            }
        }
    }

    fun executeDdl(
        config: DbExecuteDdlConfig,
        event: MuseEvent,
    ): Int =
        connectionFactory.open().use { connection ->
            connection.createStatement().use { statement ->
                applyStatementTimeout(statement, config.settings)
                statement.executeUpdate(resolveText(config.sql, event, "SQL"))
            }
        }

    fun executeScript(
        config: DbExecuteScriptConfig,
        event: MuseEvent,
    ): List<Int> {
        val script =
            config.sql?.let { resolveText(it, event, "SQL script") }
                ?: loadScript(resolveText(checkNotNull(config.scriptPath), event, "script path"))
        val statements = SqlScriptSplitter.split(script)
        if (statements.isEmpty()) return emptyList()

        return connectionFactory.open().use { connection ->
            localTransaction(connection) {
                connection.createStatement().use { statement ->
                    applyStatementTimeout(statement, config.settings)
                    val updateCounts = mutableListOf<Int>()
                    statements.forEach { sql ->
                        var resultAvailable = statement.execute(sql)
                        while (true) {
                            if (resultAvailable) {
                                statement.resultSet?.close()
                            } else {
                                val updateCount = statement.updateCount
                                if (updateCount == -1) break
                                updateCounts += updateCount
                            }
                            resultAvailable = statement.moreResults
                        }
                    }
                    updateCounts
                }
            }
        }
    }

    fun storedProcedure(
        config: DbStoredProcedureConfig,
        event: MuseEvent,
    ): DbStoredProcedureResult {
        val parsedSql = NamedParameterSql.parse(resolveText(config.sql, event, "stored procedure SQL"))
        val input = parameterResolver.resolve(config.inputParameters, event)
        val inOut = parameterResolver.resolve(config.inOutParameters, event)
        val outputByName = config.outputParameters.associateBy { it.name }
        val knownNames = input.keys + inOut.keys + outputByName.keys
        val missing = parsedSql.parameterNames.distinct().filterNot(knownNames::contains)
        if (missing.isNotEmpty()) {
            throw DbParameterException("Missing stored procedure parameter(s): ${missing.joinToString()}")
        }
        val invalidTypes = config.parameterTypes.keys.filterNot((input.keys + inOut.keys)::contains)
        if (invalidTypes.isNotEmpty()) {
            throw DbParameterException(
                "Parameter type(s) have no matching input value: ${invalidTypes.sorted().joinToString()}",
            )
        }

        return connectionFactory.open().use { connection ->
            connection.prepareCall(parsedSql.jdbcSql).use { statement ->
                statement.applySettings(config.settings)
                val outputIndexes = linkedMapOf<String, Int>()
                parsedSql.parameterNames.forEachIndexed { zeroBasedIndex, name ->
                    val index = zeroBasedIndex + 1
                    val output = outputByName[name]
                    when {
                        inOut.containsKey(name) -> {
                            statement.setJdbcValue(index, inOut[name], config.parameterTypes[name] ?: output?.type)
                            val outputType = output?.type ?: config.parameterTypes[name]
                                ?: throw DbParameterException("INOUT parameter '$name' requires a JDBC type")
                            statement.registerOutput(index, outputType)
                            outputIndexes.putIfAbsent(name, index)
                        }

                        output != null -> {
                            statement.registerOutput(index, output.type)
                            outputIndexes.putIfAbsent(name, index)
                        }

                        else -> statement.setJdbcValue(index, input[name], config.parameterTypes[name])
                    }
                }

                val resultSets = mutableListOf<List<Map<String, Any?>>>()
                val updateCounts = mutableListOf<Int>()
                var resultAvailable = statement.execute()
                while (true) {
                    if (resultAvailable) {
                        statement.resultSet?.use { resultSet ->
                            resultSets += resultSet.readAllRows(config.settings.useColumnNumbers)
                        }
                    } else {
                        val updateCount = statement.updateCount
                        if (updateCount == -1) break
                        updateCounts += updateCount
                    }
                    resultAvailable = statement.moreResults
                }

                val outputValues =
                    outputIndexes.mapValues { (_, index) ->
                        when (val value = statement.getObject(index)) {
                            is ResultSet -> value.use { it.readAllRows(config.settings.useColumnNumbers) }
                            else -> materializeJdbcValue(value)
                        }
                    }
                DbStoredProcedureResult(outputValues, resultSets, updateCounts)
            }
        }
    }

    fun queryRows(
        sql: String,
        parameters: Map<String, Any?>,
        parameterTypes: Map<String, DbParameterType> = emptyMap(),
        settings: DbQuerySettings = DbQuerySettings(),
    ): List<Map<String, Any?>> {
        val parsedSql = NamedParameterSql.parse(sql)
        return connectionFactory.open().use { connection ->
            connection.prepareStatement(parsedSql.jdbcSql).use { statement ->
                statement.applySettings(settings)
                statement.bind(parsedSql, parameters, parameterTypes)
                statement.executeQuery().use { it.readAllRows(settings.useColumnNumbers) }
            }
        }
    }

    private fun executeDml(
        config: DbParameterizedOperationConfig,
        generatedKeys: DbGeneratedKeys,
        event: MuseEvent,
    ): DbStatementResult =
        connectionFactory.open().use { connection ->
            val sql = resolveText(config.sql, event, "SQL")
            val parsedSql = NamedParameterSql.parse(sql)
            val parameters = parameterResolver.resolve(config.inputParameters, event)
            createPreparedStatement(connection, parsedSql.jdbcSql, generatedKeys).use { statement ->
                statement.applySettings(config.settings)
                statement.bind(parsedSql, parameters, config.parameterTypes)
                val affectedRows = statement.executeUpdate()
                val keys = if (generatedKeys.enabled) statement.readGeneratedKeys() else emptyMap()
                DbStatementResult(affectedRows, keys)
            }
        }

    private fun prepare(
        connection: Connection,
        config: DbParameterizedOperationConfig,
        event: MuseEvent,
    ): PreparedStatement {
        val parsedSql = NamedParameterSql.parse(resolveText(config.sql, event, "SQL"))
        val parameters = parameterResolver.resolve(config.inputParameters, event)
        return connection.prepareStatement(parsedSql.jdbcSql).also { statement ->
            try {
                statement.applySettings(config.settings)
                statement.bind(parsedSql, parameters, config.parameterTypes)
            } catch (failure: Throwable) {
                statement.close()
                throw failure
            }
        }
    }

    private fun resolveText(
        text: String,
        event: MuseEvent,
        description: String,
    ): String {
        val evaluated = expressionEvaluator.evaluate(text, event) ?: return text
        val value = evaluated.getOrNull()
            ?: throw DbParameterException("$description expression evaluated to null")
        return value.toString()
    }

    private fun loadScript(pathValue: String): String {
        val contextClassLoader = Thread.currentThread().contextClassLoader ?: JdbcOperations::class.java.classLoader
        val stream =
            when {
                pathValue.startsWith("classpath:") ->
                    contextClassLoader.getResourceAsStream(pathValue.removePrefix("classpath:").trimStart('/'))
                pathValue.startsWith("file:") -> Files.newInputStream(Path.of(URI.create(pathValue)))
                else ->
                    contextClassLoader.getResourceAsStream(pathValue.trimStart('/'))
                        ?: Path.of(pathValue).takeIf(Files::isRegularFile)?.let(Files::newInputStream)
            } ?: throw DbScriptNotFoundException(pathValue)
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private inline fun <T> localTransaction(
        connection: Connection,
        action: () -> T,
    ): T {
        if (!connection.autoCommit) return action()
        connection.autoCommit = false
        var primaryFailure: Throwable? = null
        return try {
            action().also { connection.commit() }
        } catch (failure: Throwable) {
            primaryFailure = failure
            try {
                connection.rollback()
            } catch (rollbackFailure: Throwable) {
                if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
            }
            throw failure
        } finally {
            try {
                connection.autoCommit = true
            } catch (restoreFailure: Throwable) {
                val failure = primaryFailure
                if (failure == null) throw restoreFailure
                if (restoreFailure !== failure) failure.addSuppressed(restoreFailure)
            }
        }
    }

    private fun applyStatementTimeout(
        statement: Statement,
        settings: DbQuerySettings,
    ) {
        val timeout = settings.timeoutSeconds()
        if (timeout > 0) statement.queryTimeout = timeout
    }
}

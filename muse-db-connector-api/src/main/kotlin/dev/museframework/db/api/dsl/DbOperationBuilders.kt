package dev.museframework.db.api.dsl

import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.model.DbBatchParameterSource
import dev.museframework.db.api.model.DbGeneratedKeys
import dev.museframework.db.api.model.DbOutputParameter
import dev.museframework.db.api.model.DbParameterSource
import dev.museframework.db.api.model.DbParameterType
import dev.museframework.db.api.model.DbQuerySettings
import dev.museframework.db.api.operation.DbBulkDeleteConfig
import dev.museframework.db.api.operation.DbBulkInsertConfig
import dev.museframework.db.api.operation.DbBulkUpdateConfig
import dev.museframework.db.api.operation.DbDeleteConfig
import dev.museframework.db.api.operation.DbExecuteDdlConfig
import dev.museframework.db.api.operation.DbExecuteScriptConfig
import dev.museframework.db.api.operation.DbInsertConfig
import dev.museframework.db.api.operation.DbQuerySingleConfig
import dev.museframework.db.api.operation.DbSelectConfig
import dev.museframework.db.api.operation.DbStoredProcedureConfig
import dev.museframework.db.api.operation.DbUpdateConfig
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.engine.api.streaming.StreamingStrategy
import java.sql.JDBCType
import java.time.Duration
import java.util.UUID

internal data class DbComponentMetadata(
    val docName: String,
    val docId: String,
    val docDescription: String?,
    val sourceFile: String?,
    val sourceLine: Int?,
    val sourceClass: String?,
)

@MuseDsl
abstract class DbOperationBuilder internal constructor(
    protected val configRef: DbConfigRef,
) {
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var target: String? = null
    var targetValue: String? = null

    internal fun metadata(): DbComponentMetadata {
        val callSite = CallSiteCapture.capture()
        return DbComponentMetadata(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
        )
    }

    protected fun targetConfig(): TargetConfig? =
        target?.let {
            require(it.isNotBlank()) { "target must not be blank" }
            TargetConfig(target = it, targetValue = targetValue)
        }
}

@MuseDsl
abstract class DbSqlOperationBuilder internal constructor(
    configRef: DbConfigRef,
) : DbOperationBuilder(configRef) {
    var sql: String = ""
    var queryTimeout: Duration = Duration.ZERO
    var fetchSize: Int? = null
    var maxRows: Int? = null
    var useColumnNumbers: Boolean = false

    protected fun settings(): DbQuerySettings =
        DbQuerySettings(
            queryTimeout = queryTimeout,
            fetchSize = fetchSize,
            maxRows = maxRows,
            useColumnNumbers = useColumnNumbers,
        )
}

@MuseDsl
abstract class DbParameterizedOperationBuilder internal constructor(
    configRef: DbConfigRef,
    defaultInputParameters: DbParameterSource = DbParameterSource.EMPTY,
) : DbSqlOperationBuilder(configRef) {
    var inputParameters: DbParameterSource = defaultInputParameters
    private val parameterTypes = linkedMapOf<String, DbParameterType>()

    fun inputParameters(values: Map<String, Any?>) {
        inputParameters = DbParameterSource.Values(values.toMap())
    }

    fun inputParameters(expression: String) {
        inputParameters = DbParameterSource.Expression(expression)
    }

    fun inputParameter(name: String, value: Any?) {
        require(name.isNotBlank()) { "Input parameter name must not be blank" }
        val values =
            when (val source = inputParameters) {
                is DbParameterSource.Values -> source.values.toMutableMap()
                is DbParameterSource.Expression ->
                    throw IllegalStateException("Individual parameters cannot be added to an expression parameter source")
            }
        values[name] = value
        inputParameters = DbParameterSource.Values(values)
    }

    fun parameterType(
        name: String,
        jdbcType: JDBCType,
        typeName: String? = null,
        scale: Int? = null,
    ) {
        require(name.isNotBlank()) { "Parameter type name must not be blank" }
        parameterTypes[name] = DbParameterType(jdbcType, typeName, scale)
    }

    protected fun parameterTypes(): Map<String, DbParameterType> = parameterTypes.toMap()
}

class DbSelectBuilder internal constructor(configRef: DbConfigRef) : DbParameterizedOperationBuilder(configRef) {
    var streamingStrategy: StreamingStrategy = StreamingStrategy.RepeatableInMemory()

    fun build(): DbSelectConfig {
        val metadata = metadata()
        return DbSelectConfig(
            configRef = configRef,
            sql = sql,
            inputParameters = inputParameters,
            parameterTypes = parameterTypes(),
            settings = settings(),
            streamingStrategy = streamingStrategy,
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

class DbQuerySingleBuilder internal constructor(configRef: DbConfigRef) : DbParameterizedOperationBuilder(configRef) {
    fun build(): DbQuerySingleConfig {
        val metadata = metadata()
        return DbQuerySingleConfig(
            configRef = configRef,
            sql = sql,
            inputParameters = inputParameters,
            parameterTypes = parameterTypes(),
            settings = settings(),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

@MuseDsl
abstract class DbGeneratedKeysOperationBuilder internal constructor(configRef: DbConfigRef) :
    DbParameterizedOperationBuilder(configRef) {
    var autoGenerateKeys: Boolean = false
    private val generatedKeyColumnNames = mutableListOf<String>()
    private val generatedKeyColumnIndexes = mutableListOf<Int>()

    fun generatedKeyColumn(name: String) {
        require(name.isNotBlank()) { "Generated-key column name must not be blank" }
        generatedKeyColumnNames += name
        autoGenerateKeys = true
    }

    fun generatedKeyColumn(index: Int) {
        require(index > 0) { "Generated-key column indexes are one-based" }
        generatedKeyColumnIndexes += index
        autoGenerateKeys = true
    }

    protected fun generatedKeys(): DbGeneratedKeys =
        DbGeneratedKeys(autoGenerateKeys, generatedKeyColumnNames.toList(), generatedKeyColumnIndexes.toList())
}

class DbInsertBuilder internal constructor(configRef: DbConfigRef) : DbGeneratedKeysOperationBuilder(configRef) {
    fun build(): DbInsertConfig {
        val metadata = metadata()
        return DbInsertConfig(
            configRef = configRef,
            sql = sql,
            inputParameters = inputParameters,
            parameterTypes = parameterTypes(),
            settings = settings(),
            generatedKeys = generatedKeys(),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

class DbUpdateBuilder internal constructor(configRef: DbConfigRef) : DbGeneratedKeysOperationBuilder(configRef) {
    fun build(): DbUpdateConfig {
        val metadata = metadata()
        return DbUpdateConfig(
            configRef = configRef,
            sql = sql,
            inputParameters = inputParameters,
            parameterTypes = parameterTypes(),
            settings = settings(),
            generatedKeys = generatedKeys(),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

class DbDeleteBuilder internal constructor(configRef: DbConfigRef) : DbParameterizedOperationBuilder(configRef) {
    fun build(): DbDeleteConfig {
        val metadata = metadata()
        return DbDeleteConfig(
            configRef = configRef,
            sql = sql,
            inputParameters = inputParameters,
            parameterTypes = parameterTypes(),
            settings = settings(),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

@MuseDsl
abstract class DbBulkOperationBuilder internal constructor(configRef: DbConfigRef) : DbSqlOperationBuilder(configRef) {
    var batchParameters: DbBatchParameterSource = DbBatchParameterSource.PAYLOAD
    private val parameterTypes = linkedMapOf<String, DbParameterType>()

    fun batchParameters(values: List<Map<String, Any?>>) {
        batchParameters = DbBatchParameterSource.Values(values.map { it.toMap() })
    }

    fun batchParameters(expression: String) {
        batchParameters = DbBatchParameterSource.Expression(expression)
    }

    fun parameterType(
        name: String,
        jdbcType: JDBCType,
        typeName: String? = null,
        scale: Int? = null,
    ) {
        require(name.isNotBlank()) { "Parameter type name must not be blank" }
        parameterTypes[name] = DbParameterType(jdbcType, typeName, scale)
    }

    protected fun parameterTypes(): Map<String, DbParameterType> = parameterTypes.toMap()
}

class DbBulkInsertBuilder internal constructor(configRef: DbConfigRef) : DbBulkOperationBuilder(configRef) {
    fun build(): DbBulkInsertConfig {
        val metadata = metadata()
        return DbBulkInsertConfig(
            configRef = configRef,
            sql = sql,
            batchParameters = batchParameters,
            parameterTypes = parameterTypes(),
            settings = settings(),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

class DbBulkUpdateBuilder internal constructor(configRef: DbConfigRef) : DbBulkOperationBuilder(configRef) {
    fun build(): DbBulkUpdateConfig {
        val metadata = metadata()
        return DbBulkUpdateConfig(
            configRef = configRef,
            sql = sql,
            batchParameters = batchParameters,
            parameterTypes = parameterTypes(),
            settings = settings(),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

class DbBulkDeleteBuilder internal constructor(configRef: DbConfigRef) : DbBulkOperationBuilder(configRef) {
    fun build(): DbBulkDeleteConfig {
        val metadata = metadata()
        return DbBulkDeleteConfig(
            configRef = configRef,
            sql = sql,
            batchParameters = batchParameters,
            parameterTypes = parameterTypes(),
            settings = settings(),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

class DbExecuteDdlBuilder internal constructor(configRef: DbConfigRef) : DbSqlOperationBuilder(configRef) {
    fun build(): DbExecuteDdlConfig {
        val metadata = metadata()
        return DbExecuteDdlConfig(
            configRef = configRef,
            sql = sql,
            settings = settings(),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

class DbExecuteScriptBuilder internal constructor(configRef: DbConfigRef) : DbOperationBuilder(configRef) {
    var sql: String? = null
    var scriptPath: String? = null
    var queryTimeout: Duration = Duration.ZERO

    fun build(): DbExecuteScriptConfig {
        val metadata = metadata()
        return DbExecuteScriptConfig(
            configRef = configRef,
            sql = sql,
            scriptPath = scriptPath,
            settings = DbQuerySettings(queryTimeout = queryTimeout),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

class DbStoredProcedureBuilder internal constructor(configRef: DbConfigRef) : DbParameterizedOperationBuilder(configRef) {
    var inOutParameters: DbParameterSource = DbParameterSource.EMPTY
    private val outputParameters = mutableListOf<DbOutputParameter>()

    fun inOutParameters(values: Map<String, Any?>) {
        inOutParameters = DbParameterSource.Values(values.toMap())
    }

    fun inOutParameters(expression: String) {
        inOutParameters = DbParameterSource.Expression(expression)
    }

    fun outputParameter(
        name: String,
        jdbcType: JDBCType,
        typeName: String? = null,
        scale: Int? = null,
    ) {
        outputParameters += DbOutputParameter(name, DbParameterType(jdbcType, typeName, scale))
    }

    fun build(): DbStoredProcedureConfig {
        val metadata = metadata()
        return DbStoredProcedureConfig(
            configRef = configRef,
            sql = sql,
            inputParameters = inputParameters,
            inOutParameters = inOutParameters,
            outputParameters = outputParameters.toList(),
            parameterTypes = parameterTypes(),
            settings = settings(),
            targetConfig = targetConfig(),
            docName = metadata.docName,
            docId = metadata.docId,
            docDescription = metadata.docDescription,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
            sourceClass = metadata.sourceClass,
        )
    }
}

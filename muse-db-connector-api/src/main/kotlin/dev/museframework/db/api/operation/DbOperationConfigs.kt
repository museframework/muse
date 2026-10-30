package dev.museframework.db.api.operation

import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.model.DbBatchParameterSource
import dev.museframework.db.api.model.DbGeneratedKeys
import dev.museframework.db.api.model.DbOutputParameter
import dev.museframework.db.api.model.DbParameterSource
import dev.museframework.db.api.model.DbParameterType
import dev.museframework.db.api.model.DbQuerySettings
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.engine.api.streaming.StreamingStrategy
import java.util.UUID

sealed interface DbOperationConfig : Processor {
    val configRef: DbConfigRef
    val targetConfig: TargetConfig?
}

sealed interface DbSqlOperationConfig : DbOperationConfig {
    val sql: String
    val settings: DbQuerySettings
}

sealed interface DbParameterizedOperationConfig : DbSqlOperationConfig {
    val inputParameters: DbParameterSource
    val parameterTypes: Map<String, DbParameterType>
}

data class DbSelectConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val inputParameters: DbParameterSource = DbParameterSource.EMPTY,
    override val parameterTypes: Map<String, DbParameterType> = emptyMap(),
    override val settings: DbQuerySettings = DbQuerySettings(),
    val streamingStrategy: StreamingStrategy = StreamingStrategy.RepeatableInMemory(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbParameterizedOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
    }
}

data class DbQuerySingleConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val inputParameters: DbParameterSource = DbParameterSource.EMPTY,
    override val parameterTypes: Map<String, DbParameterType> = emptyMap(),
    override val settings: DbQuerySettings = DbQuerySettings(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbParameterizedOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
    }
}

data class DbInsertConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val inputParameters: DbParameterSource = DbParameterSource.EMPTY,
    override val parameterTypes: Map<String, DbParameterType> = emptyMap(),
    override val settings: DbQuerySettings = DbQuerySettings(),
    val generatedKeys: DbGeneratedKeys = DbGeneratedKeys(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbParameterizedOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
    }
}

data class DbUpdateConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val inputParameters: DbParameterSource = DbParameterSource.EMPTY,
    override val parameterTypes: Map<String, DbParameterType> = emptyMap(),
    override val settings: DbQuerySettings = DbQuerySettings(),
    val generatedKeys: DbGeneratedKeys = DbGeneratedKeys(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbParameterizedOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
    }
}

data class DbDeleteConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val inputParameters: DbParameterSource = DbParameterSource.EMPTY,
    override val parameterTypes: Map<String, DbParameterType> = emptyMap(),
    override val settings: DbQuerySettings = DbQuerySettings(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbParameterizedOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
    }
}

sealed interface DbBulkOperationConfig : DbSqlOperationConfig {
    val batchParameters: DbBatchParameterSource
    val parameterTypes: Map<String, DbParameterType>
}

data class DbBulkInsertConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val batchParameters: DbBatchParameterSource = DbBatchParameterSource.PAYLOAD,
    override val parameterTypes: Map<String, DbParameterType> = emptyMap(),
    override val settings: DbQuerySettings = DbQuerySettings(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbBulkOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
    }
}

data class DbBulkUpdateConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val batchParameters: DbBatchParameterSource = DbBatchParameterSource.PAYLOAD,
    override val parameterTypes: Map<String, DbParameterType> = emptyMap(),
    override val settings: DbQuerySettings = DbQuerySettings(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbBulkOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
    }
}

data class DbBulkDeleteConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val batchParameters: DbBatchParameterSource = DbBatchParameterSource.PAYLOAD,
    override val parameterTypes: Map<String, DbParameterType> = emptyMap(),
    override val settings: DbQuerySettings = DbQuerySettings(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbBulkOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
    }
}

data class DbExecuteDdlConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val settings: DbQuerySettings = DbQuerySettings(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbSqlOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
    }
}

data class DbExecuteScriptConfig(
    override val configRef: DbConfigRef,
    val sql: String? = null,
    val scriptPath: String? = null,
    val settings: DbQuerySettings = DbQuerySettings(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbOperationConfig {
    init {
        require((sql != null) xor (scriptPath != null)) { "Exactly one of sql or scriptPath is required" }
        require(sql == null || sql.isNotBlank()) { "sql must not be blank" }
        require(scriptPath == null || scriptPath.isNotBlank()) { "scriptPath must not be blank" }
    }
}

data class DbStoredProcedureConfig(
    override val configRef: DbConfigRef,
    override val sql: String,
    override val inputParameters: DbParameterSource = DbParameterSource.EMPTY,
    val inOutParameters: DbParameterSource = DbParameterSource.EMPTY,
    val outputParameters: List<DbOutputParameter> = emptyList(),
    override val parameterTypes: Map<String, DbParameterType> = emptyMap(),
    override val settings: DbQuerySettings = DbQuerySettings(),
    override val targetConfig: TargetConfig? = null,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : DbParameterizedOperationConfig {
    init {
        require(sql.isNotBlank()) { "sql must not be blank" }
        require(outputParameters.map { it.name }.distinct().size == outputParameters.size) {
            "Output parameter names must be unique"
        }
    }
}

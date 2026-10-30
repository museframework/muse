package dev.museframework.db.api.model

import java.sql.JDBCType

sealed interface DbParameterSource {
    data class Values(
        val values: Map<String, Any?> = emptyMap(),
    ) : DbParameterSource

    data class Expression(
        val expression: String,
    ) : DbParameterSource {
        init {
            require(expression.isNotBlank()) { "Parameter expression must not be blank" }
        }
    }

    companion object {
        val EMPTY: DbParameterSource = Values()
    }
}

sealed interface DbBatchParameterSource {
    data class Values(
        val values: List<Map<String, Any?>>,
    ) : DbBatchParameterSource

    data class Expression(
        val expression: String,
    ) : DbBatchParameterSource {
        init {
            require(expression.isNotBlank()) { "Batch parameter expression must not be blank" }
        }
    }

    companion object {
        val PAYLOAD: DbBatchParameterSource = Expression("#[payload]")
    }
}

data class DbParameterType(
    val jdbcType: JDBCType,
    val typeName: String? = null,
    val scale: Int? = null,
) {
    init {
        require(typeName == null || typeName.isNotBlank()) { "typeName must not be blank" }
        require(scale == null || scale >= 0) { "scale must be greater than or equal to zero" }
    }
}

data class DbOutputParameter(
    val name: String,
    val type: DbParameterType,
) {
    init {
        require(name.isNotBlank()) { "Output parameter name must not be blank" }
    }
}

data class DbGeneratedKeys(
    val enabled: Boolean = false,
    val columnNames: List<String> = emptyList(),
    val columnIndexes: List<Int> = emptyList(),
) {
    init {
        require(columnNames.none(String::isBlank)) { "Generated-key column names must not be blank" }
        require(columnIndexes.all { it > 0 }) { "Generated-key column indexes are one-based" }
        require(columnNames.isEmpty() || columnIndexes.isEmpty()) {
            "Generated-key column names and indexes are mutually exclusive"
        }
        require(enabled || columnNames.isEmpty() && columnIndexes.isEmpty()) {
            "Generated-key columns require enabled=true"
        }
    }
}

package dev.museframework.connectors.db.sql

import dev.museframework.connectors.db.DbParameterException
import dev.museframework.db.api.model.DbParameterType
import dev.museframework.db.api.model.DbQuerySettings
import java.sql.Array as SqlArray
import java.sql.Blob
import java.sql.CallableStatement
import java.sql.Clob
import java.sql.NClob
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLXML
import java.sql.Statement
import java.sql.Types

internal fun PreparedStatement.applySettings(settings: DbQuerySettings) {
    val timeoutSeconds = settings.timeoutSeconds()
    if (timeoutSeconds > 0) queryTimeout = timeoutSeconds
    settings.fetchSize?.let { fetchSize = it }
    settings.maxRows?.let { maxRows = it }
}

internal fun PreparedStatement.bind(
    parsedSql: ParsedSql,
    parameters: Map<String, Any?>,
    parameterTypes: Map<String, DbParameterType>,
) {
    val missing = parsedSql.parameterNames.distinct().filterNot(parameters::containsKey)
    if (missing.isNotEmpty()) {
        throw DbParameterException("Missing input parameter(s): ${missing.joinToString()}")
    }
    val invalidTypes = parameterTypes.keys.filterNot(parameters::containsKey)
    if (invalidTypes.isNotEmpty()) {
        throw DbParameterException(
            "Parameter type(s) have no matching input value: ${invalidTypes.sorted().joinToString()}",
        )
    }

    parsedSql.parameterNames.forEachIndexed { index, name ->
        setJdbcValue(index + 1, parameters.getValueOrNull(name), parameterTypes[name])
    }
}

internal fun PreparedStatement.setJdbcValue(
    index: Int,
    value: Any?,
    type: DbParameterType?,
) {
    if (type == null) {
        if (value == null) setNull(index, Types.NULL) else setObject(index, value)
        return
    }

    val jdbcType = type.jdbcType.vendorTypeNumber
    val typeName = type.typeName
    val scale = type.scale
    when {
        value == null && typeName != null -> setNull(index, jdbcType, typeName)
        value == null -> setNull(index, jdbcType)
        scale != null -> setObject(index, value, jdbcType, scale)
        else -> setObject(index, value, jdbcType)
    }
}

internal fun CallableStatement.registerOutput(
    index: Int,
    type: DbParameterType,
) {
    val typeName = type.typeName
    val scale = type.scale
    when {
        typeName != null -> registerOutParameter(index, type.jdbcType.vendorTypeNumber, typeName)
        scale != null -> registerOutParameter(index, type.jdbcType.vendorTypeNumber, scale)
        else -> registerOutParameter(index, type.jdbcType.vendorTypeNumber)
    }
}

internal fun ResultSet.readCurrentRow(useColumnNumbers: Boolean): Map<String, Any?> {
    val metadata = metaData
    val row = LinkedHashMap<String, Any?>(metadata.columnCount)
    for (index in 1..metadata.columnCount) {
        val key = if (useColumnNumbers) index.toString() else metadata.getColumnLabel(index)
        row[key] = materializeJdbcValue(getObject(index))
    }
    return row
}

internal fun ResultSet.readAllRows(useColumnNumbers: Boolean): List<Map<String, Any?>> {
    val rows = mutableListOf<Map<String, Any?>>()
    while (next()) rows += readCurrentRow(useColumnNumbers)
    return rows
}

internal fun PreparedStatement.readGeneratedKeys(): Map<String, Any?> =
    generatedKeys.use { keys ->
        if (!keys.next()) return@use emptyMap()
        keys.readCurrentRow(useColumnNumbers = false)
    }

internal fun createPreparedStatement(
    connection: java.sql.Connection,
    sql: String,
    generatedKeys: dev.museframework.db.api.model.DbGeneratedKeys,
): PreparedStatement =
    when {
        !generatedKeys.enabled -> connection.prepareStatement(sql)
        generatedKeys.columnNames.isNotEmpty() -> connection.prepareStatement(sql, generatedKeys.columnNames.toTypedArray())
        generatedKeys.columnIndexes.isNotEmpty() -> connection.prepareStatement(sql, generatedKeys.columnIndexes.toIntArray())
        else -> connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
    }

private fun Map<String, Any?>.getValueOrNull(name: String): Any? = this[name]

internal fun materializeJdbcValue(value: Any?): Any? =
    when (value) {
        is Blob ->
            try {
                value.binaryStream.use { it.readBytes() }
            } finally {
                value.free()
            }

        is NClob ->
            try {
                value.characterStream.use { it.readText() }
            } finally {
                value.free()
            }

        is Clob ->
            try {
                value.characterStream.use { it.readText() }
            } finally {
                value.free()
            }

        is SQLXML ->
            try {
                value.string
            } finally {
                value.free()
            }

        is SqlArray ->
            try {
                value.array
            } finally {
                value.free()
            }

        else -> value
    }

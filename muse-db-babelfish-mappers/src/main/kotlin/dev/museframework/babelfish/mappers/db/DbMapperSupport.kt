package dev.museframework.babelfish.mappers.db

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.model.DbBatchParameterSource
import dev.museframework.db.api.model.DbGeneratedKeys
import dev.museframework.db.api.model.DbParameterSource
import dev.museframework.db.api.model.DbParameterType
import dev.museframework.db.api.model.DbQuerySettings
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.engine.api.streaming.StreamingStrategy
import java.sql.JDBCType
import java.time.Duration
import java.util.UUID

internal data class DbMappedMetadata(
    val docName: String,
    val docId: String,
    val sourceFile: String,
    val sourceLine: Int,
)

internal fun RawElement.dbMetadata(ctx: MappingContext): DbMappedMetadata =
    DbMappedMetadata(
        docName = attrs["doc:name"].orEmpty(),
        docId = attrs["doc:id"] ?: UUID.randomUUID().toString(),
        sourceFile = ctx.sourceFile.toString(),
        sourceLine = location.line,
    )

internal fun RawElement.dbConfigRef(): DbConfigRef = DbConfigRef.ByName(requireAttr("config-ref", location))

internal fun RawElement.dbSql(): String =
    descendants()
        .firstOrNull { it.tag == "sql" }
        ?.textContent
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: attrs["sql"]?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("db:$tag requires SQL text")

internal fun RawElement.dbInputParameters(default: DbParameterSource = DbParameterSource.EMPTY): DbParameterSource =
    descendants()
        .firstOrNull { it.tag == "input-parameters" }
        ?.textContent
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(DbParameterSource::Expression)
        ?: default

internal fun RawElement.dbBatchParameters(): DbBatchParameterSource =
    descendants()
        .firstOrNull { it.tag == "input-parameters" }
        ?.textContent
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(DbBatchParameterSource::Expression)
        ?: DbBatchParameterSource.PAYLOAD

internal fun RawElement.dbParameterTypes(): Map<String, DbParameterType> =
    descendants()
        .filter { it.tag == "parameter-type" }
        .associate { parameter ->
            val name = parameter.attrs["key"] ?: parameter.attrs["name"]
                ?: throw IllegalArgumentException("db:parameter-type requires key")
            val typeName = parameter.attrs["type"] ?: parameter.attrs["jdbcType"]
                ?: throw IllegalArgumentException("db:parameter-type '$name' requires type")
            name to
                DbParameterType(
                    jdbcType = parseJdbcType(typeName),
                    typeName = parameter.attrs["customType"],
                    scale = parameter.attrs["scale"]?.toInt(),
                )
        }

internal fun RawElement.dbSettings(): DbQuerySettings {
    val timeout = attrs["queryTimeout"]?.toLongOrNull() ?: 0L
    val unit = attrs["queryTimeoutUnit"]?.uppercase() ?: "SECONDS"
    val duration =
        when (unit) {
            "NANOSECONDS" -> Duration.ofNanos(timeout)
            "MICROSECONDS" -> Duration.ofNanos(Math.multiplyExact(timeout, 1_000L))
            "MILLISECONDS" -> Duration.ofMillis(timeout)
            "SECONDS" -> Duration.ofSeconds(timeout)
            "MINUTES" -> Duration.ofMinutes(timeout)
            "HOURS" -> Duration.ofHours(timeout)
            "DAYS" -> Duration.ofDays(timeout)
            else -> throw IllegalArgumentException("Unsupported query timeout unit '$unit'")
        }
    return DbQuerySettings(
        queryTimeout = duration,
        fetchSize = attrs["fetchSize"]?.toInt(),
        maxRows = attrs["maxRows"]?.toInt(),
        useColumnNumbers = attrs["useColumnNumbers"]?.toBooleanStrictOrNull() ?: false,
    )
}

internal data class DbMappedInOutParameters(
    val source: DbParameterSource,
    val types: Map<String, DbParameterType>,
)

/**
 * Maps `<db:in-out-parameters>`. Child `<db:in-out-parameter key value [type]/>` elements become
 * [DbParameterSource.Values] (and JDBC types when declared); otherwise the element text is an expression.
 */
internal fun RawElement.dbInOutParameters(): DbMappedInOutParameters {
    val container = descendants().firstOrNull { it.tag == "in-out-parameters" }
        ?: return DbMappedInOutParameters(DbParameterSource.EMPTY, emptyMap())
    val entries = container.children.filter { it.tag == "in-out-parameter" }
    if (entries.isEmpty()) {
        val expression =
            container.textContent
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(DbParameterSource::Expression)
                ?: DbParameterSource.EMPTY
        return DbMappedInOutParameters(expression, emptyMap())
    }
    val values = linkedMapOf<String, Any?>()
    val types = linkedMapOf<String, DbParameterType>()
    entries.forEach { entry ->
        val name = entry.attrs["key"] ?: entry.attrs["name"]
            ?: throw IllegalArgumentException("db:in-out-parameter requires key")
        values[name] = entry.attrs["value"] ?: entry.textContent?.trim()?.takeIf(String::isNotEmpty)
        (entry.attrs["type"] ?: entry.attrs["jdbcType"])?.let { type ->
            types[name] =
                DbParameterType(
                    jdbcType = parseJdbcType(type),
                    typeName = entry.attrs["customType"],
                    scale = entry.attrs["scale"]?.toInt(),
                )
        }
    }
    return DbMappedInOutParameters(DbParameterSource.Values(values), types)
}

private val dbStreamingStrategyTags =
    setOf("non-repeatable-iterable", "repeatable-in-memory-iterable", "repeatable-file-store-iterable")

/**
 * Maps a Mule iterable streaming strategy, declared either directly under the operation or inside
 * `<db:streaming-strategy>`. Absent configuration keeps the default repeatable in-memory strategy.
 */
internal fun RawElement.dbStreamingStrategy(): StreamingStrategy {
    val strategy = descendants().firstOrNull { it.tag in dbStreamingStrategyTags }
        ?: return StreamingStrategy.RepeatableInMemory()
    return when (strategy.tag) {
        "non-repeatable-iterable" -> StreamingStrategy.NonRepeatable
        "repeatable-in-memory-iterable" -> {
            val defaults = StreamingStrategy.RepeatableInMemory()
            StreamingStrategy.RepeatableInMemory(
                initialObjectBufferSize = strategy.attrs["initialBufferSize"]?.toInt() ?: defaults.initialObjectBufferSize,
                objectBufferSizeIncrement =
                    strategy.attrs["bufferSizeIncrement"]?.toInt() ?: defaults.objectBufferSizeIncrement,
                maxObjectBufferSize = strategy.attrs["maxBufferSize"]?.toInt() ?: defaults.maxObjectBufferSize,
            )
        }
        else ->
            throw IllegalArgumentException(
                "db:${strategy.tag} is not supported: repeatable file-store object streaming is unavailable",
            )
    }
}

private val generatedKeyNameTags = setOf("auto-generated-keys-column-name", "column-name")
private val generatedKeyIndexTags = setOf("auto-generated-keys-column-index", "column-index")

internal fun RawElement.dbGeneratedKeys(): DbGeneratedKeys {
    val names =
        (
            attrs["autoGeneratedKeysColumnNames"].csv() +
                descendants().filter { it.tag in generatedKeyNameTags }.flatMap { it.keyValues() } +
                descendants().filter { it.tag == "auto-generated-keys-column-names" && it.children.isEmpty() }
                    .flatMap { it.textContent.csv() }
        ).distinct()
    val indexes =
        (
            attrs["autoGeneratedKeysColumnIndexes"].csv() +
                descendants().filter { it.tag in generatedKeyIndexTags }.flatMap { it.keyValues() } +
                descendants().filter { it.tag == "auto-generated-keys-column-indexes" && it.children.isEmpty() }
                    .flatMap { it.textContent.csv() }
        ).map(String::toInt).distinct()
    val enabled = attrs["autoGenerateKeys"]?.toBooleanStrictOrNull() ?: (names.isNotEmpty() || indexes.isNotEmpty())
    return DbGeneratedKeys(enabled, names, indexes)
}

private fun RawElement.keyValues(): Sequence<String> =
    sequenceOf(attrs["value"], textContent).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.take(1)

internal fun RawElement.dbTarget(): TargetConfig? =
    attrs["target"]?.let { TargetConfig(target = it, targetValue = attrs["targetValue"]) }

internal fun RawElement.descendants(): Sequence<RawElement> =
    children.asSequence().flatMap { child -> sequenceOf(child) + child.descendants() }

internal fun parseJdbcType(value: String): JDBCType =
    try {
        JDBCType.valueOf(value.uppercase())
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("Unsupported JDBC type '$value'", failure)
    }

internal fun String?.csv(): List<String> =
    this?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

internal fun String.kotlinLiteral(): String =
    buildString(length + 2) {
        append('"')
        this@kotlinLiteral.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

internal fun DbConfigRef.name(): String =
    when (this) {
        is DbConfigRef.ByName -> name
        is DbConfigRef.ByReference -> config.name
    }

internal fun RawElement.rejectUnsupportedAttributes(
    component: String,
    names: Set<String>,
) {
    names.forEach { name ->
        if (attrs.containsKey(name)) {
            throw IllegalArgumentException("db:$component does not support attribute '$name'")
        }
    }
}

private val allDbSettingNames = setOf("queryTimeout", "fetchSize", "maxRows", "useColumnNumbers")

internal fun StringBuilder.emitSettings(
    settings: DbQuerySettings,
    indent: String = "    ",
    settingNames: Set<String> = allDbSettingNames,
) {
    if ("queryTimeout" in settingNames && !settings.queryTimeout.isZero) {
        appendLine("${indent}queryTimeout = Duration.ofNanos(${settings.queryTimeout.toNanos()}L)")
    }
    if ("fetchSize" in settingNames) {
        settings.fetchSize?.let { appendLine("${indent}fetchSize = $it") }
    }
    if ("maxRows" in settingNames) {
        settings.maxRows?.let { appendLine("${indent}maxRows = $it") }
    }
    if ("useColumnNumbers" in settingNames && settings.useColumnNumbers) {
        appendLine("${indent}useColumnNumbers = true")
    }
}

internal fun CodegenContext.addSettingsImports(settings: DbQuerySettings) {
    if (!settings.queryTimeout.isZero) addImport("java.time.Duration")
}

internal fun StringBuilder.emitTarget(
    target: TargetConfig?,
    indent: String = "    ",
) {
    target ?: return
    appendLine("${indent}target = ${target.target.kotlinLiteral()}")
    target.targetValue?.let { appendLine("${indent}targetValue = ${it.kotlinLiteral()}") }
}

internal fun StringBuilder.emitParameterTypes(
    parameterTypes: Map<String, DbParameterType>,
    ctx: CodegenContext,
    indent: String = "    ",
) {
    if (parameterTypes.isEmpty()) return
    ctx.addImport("java.sql.JDBCType")
    parameterTypes.forEach { (name, type) ->
        append("${indent}parameterType(${name.kotlinLiteral()}, JDBCType.${type.jdbcType}")
        type.typeName?.let { append(", typeName = ${it.kotlinLiteral()}") }
        type.scale?.let { append(", scale = $it") }
        appendLine(")")
    }
}

internal fun StringBuilder.emitInputParameters(
    source: DbParameterSource,
    indent: String = "    ",
) {
    when (source) {
        is DbParameterSource.Expression -> appendLine("${indent}inputParameters(${source.expression.kotlinLiteral()})")
        is DbParameterSource.Values ->
            source.values.forEach { (name, value) ->
                appendLine("${indent}inputParameter(${name.kotlinLiteral()}, ${value.kotlinValue()})")
            }
    }
}

internal fun StringBuilder.emitBatchParameters(
    source: DbBatchParameterSource,
    indent: String = "    ",
) {
    when (source) {
        is DbBatchParameterSource.Expression -> appendLine("${indent}batchParameters(${source.expression.kotlinLiteral()})")
        is DbBatchParameterSource.Values -> {
            appendLine("${indent}batchParameters(listOf(")
            source.values.forEach { row ->
                val values =
                    row.entries.joinToString { (key, value) ->
                        "${key.kotlinLiteral()} to ${value.kotlinValue()}"
                    }
                appendLine("$indent    mapOf($values),")
            }
            appendLine("${indent}))")
        }
    }
}

private fun Any?.kotlinValue(): String =
    when (this) {
        null -> "null"
        is String -> kotlinLiteral()
        is Char -> toString().kotlinLiteral() + ".single()"
        is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> toString()
        else -> throw IllegalArgumentException("Cannot emit database parameter value of type ${this::class.java.name}")
    }

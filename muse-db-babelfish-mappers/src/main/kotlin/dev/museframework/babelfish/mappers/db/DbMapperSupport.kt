package dev.museframework.babelfish.mappers.db

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.joinToCode
import dev.museframework.babelfish.emitter.kotlinStringLiteral
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

private val RawElement.dbText: String? get() = untrimmedTextContent ?: textContent

internal fun RawElement.dbSql(): String =
    dbOptionalSql() ?: throw IllegalArgumentException("db:$tag requires SQL text")

internal fun RawElement.dbOptionalSql(): String? =
    descendants()
        .firstOrNull { it.tag == "sql" }
        ?.dbText
        ?.takeIf(String::isNotBlank)
        ?: attrs["sql"]?.takeIf(String::isNotBlank)

internal fun RawElement.dbInputParameters(default: DbParameterSource = DbParameterSource.EMPTY): DbParameterSource =
    descendants()
        .firstOrNull { it.tag == "input-parameters" }
        ?.dbText
        ?.takeIf(String::isNotBlank)
        ?.let(DbParameterSource::Expression)
        ?: default

internal fun RawElement.dbBatchParameters(): DbBatchParameterSource =
    descendants()
        .firstOrNull { it.tag == "input-parameters" }
        ?.dbText
        ?.takeIf(String::isNotBlank)
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
            container.dbText?.takeIf(String::isNotBlank)
                ?.let(DbParameterSource::Expression)
                ?: DbParameterSource.EMPTY
        return DbMappedInOutParameters(expression, emptyMap())
    }
    val values = linkedMapOf<String, Any?>()
    val types = linkedMapOf<String, DbParameterType>()
    entries.forEach { entry ->
        val name = entry.attrs["key"] ?: entry.attrs["name"]
            ?: throw IllegalArgumentException("db:in-out-parameter requires key")
        values[name] = entry.attrs["value"] ?: entry.dbText?.takeIf(String::isNotBlank)
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

internal fun dbDsl(name: String) = MemberName("dev.museframework.db.api.dsl", name)

internal fun DbConfigRef.code(): CodeBlock {
    val name = when (this) {
        is DbConfigRef.ByName -> name
        is DbConfigRef.ByReference -> config.name
    }
    return CodeBlock.of("%T.ByName(%L)", DbConfigRef::class, kotlinStringLiteral(name))
}

internal fun CodeBlock.Builder.emitTimeout(settings: DbQuerySettings) {
    if (!settings.queryTimeout.isZero) {
        addStatement(
            "queryTimeout = %T.ofSeconds(%LL, %LL)",
            Duration::class, settings.queryTimeout.seconds, settings.queryTimeout.nano,
        )
    }
}

internal fun CodeBlock.Builder.emitRowSettings(settings: DbQuerySettings) {
    emitTimeout(settings)
    settings.fetchSize?.let { addStatement("fetchSize = %L", it) }
    settings.maxRows?.let { addStatement("maxRows = %L", it) }
}

internal fun CodeBlock.Builder.emitSettings(settings: DbQuerySettings) {
    emitRowSettings(settings)
    if (settings.useColumnNumbers) addStatement("useColumnNumbers = true")
}

internal fun CodeBlock.Builder.emitTarget(target: TargetConfig?) {
    target ?: return
    addStatement("target = %L", kotlinStringLiteral(target.target))
    target.targetValue?.let { addStatement("targetValue = %L", kotlinStringLiteral(it)) }
}

internal fun CodeBlock.Builder.emitTypedParameter(functionName: String, name: String, type: DbParameterType) {
    val arguments = buildCodeBlock {
        add("%L, %T.%N", kotlinStringLiteral(name), JDBCType::class, type.jdbcType.name)
        type.typeName?.let { add(", typeName = %L", kotlinStringLiteral(it)) }
        type.scale?.let { add(", scale = %L", it) }
    }
    addStatement("%N(%L)", functionName, arguments)
}

internal fun CodeBlock.Builder.emitInputParameters(source: DbParameterSource) {
    when (source) {
        is DbParameterSource.Expression -> addStatement("inputParameters(%L)", kotlinStringLiteral(source.expression))
        is DbParameterSource.Values -> source.values.forEach { (name, value) ->
            addStatement("inputParameter(%L, %L)", kotlinStringLiteral(name), value.parameterValue())
        }
    }
}

internal fun CodeBlock.Builder.emitBatchParameters(source: DbBatchParameterSource) {
    when (source) {
        is DbBatchParameterSource.Expression -> addStatement("batchParameters(%L)", kotlinStringLiteral(source.expression))
        is DbBatchParameterSource.Values -> {
            val rows = source.values.map { it.parameterMap() }
            addStatement("batchParameters(listOf(%L))", rows.joinToCode())
        }
    }
}

internal fun Map<String, Any?>.parameterMap(): CodeBlock {
    val entries = map { (key, value) -> CodeBlock.of("%L to %L", kotlinStringLiteral(key), value.parameterValue()) }
    return CodeBlock.of("mapOf(%L)", entries.joinToCode())
}

private fun Any?.parameterValue(): CodeBlock =
    when (this) {
        null, is Boolean, is Int -> CodeBlock.of("%L", this)
        is String -> kotlinStringLiteral(this)
        is Char -> CodeBlock.of("%L.single()", kotlinStringLiteral(toString()))
        is Byte -> CodeBlock.of("(%L).toByte()", this)
        is Short -> CodeBlock.of("(%L).toShort()", this)
        is Long -> if (this == Long.MIN_VALUE) CodeBlock.of("%T.MIN_VALUE", Long::class) else CodeBlock.of("%LL", this)
        is Float -> if (isFinite()) CodeBlock.of("%LF", this) else CodeBlock.of("%T.fromBits(%L)", Float::class, toRawBits())
        is Double -> if (isFinite()) CodeBlock.of("%L", this) else CodeBlock.of("%T.fromBits(%LL)", Double::class, toRawBits())
        else -> throw IllegalArgumentException("Cannot emit database parameter value of type ${this::class.java.name}")
    }

package dev.museframework.babelfish.mappers.db

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.model.DbOutputParameter
import dev.museframework.db.api.model.DbParameterSource
import dev.museframework.db.api.operation.DbBulkDeleteConfig
import dev.museframework.db.api.operation.DbBulkInsertConfig
import dev.museframework.db.api.operation.DbBulkOperationConfig
import dev.museframework.db.api.operation.DbBulkUpdateConfig
import dev.museframework.db.api.operation.DbDeleteConfig
import dev.museframework.db.api.operation.DbExecuteDdlConfig
import dev.museframework.db.api.operation.DbExecuteScriptConfig
import dev.museframework.db.api.operation.DbInsertConfig
import dev.museframework.db.api.operation.DbOperationConfig
import dev.museframework.db.api.operation.DbParameterizedOperationConfig
import dev.museframework.db.api.operation.DbQuerySingleConfig
import dev.museframework.db.api.operation.DbSelectConfig
import dev.museframework.db.api.operation.DbStoredProcedureConfig
import dev.museframework.db.api.operation.DbUpdateConfig
import dev.museframework.engine.api.streaming.StreamingStrategy

object DbSelectMapper : ElementMapper<DbSelectConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbSelectConfig {
        val metadata = element.dbMetadata(ctx)
        return DbSelectConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            inputParameters = element.dbInputParameters(),
            parameterTypes = element.dbParameterTypes(),
            settings = element.dbSettings(),
            streamingStrategy = element.dbStreamingStrategy(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbQuerySingleMapper : ElementMapper<DbQuerySingleConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbQuerySingleConfig {
        val metadata = element.dbMetadata(ctx)
        return DbQuerySingleConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            inputParameters = element.dbInputParameters(),
            parameterTypes = element.dbParameterTypes(),
            settings = element.dbSettings(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbInsertMapper : ElementMapper<DbInsertConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbInsertConfig {
        val metadata = element.dbMetadata(ctx)
        return DbInsertConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            inputParameters = element.dbInputParameters(),
            parameterTypes = element.dbParameterTypes(),
            settings = element.dbSettings(),
            generatedKeys = element.dbGeneratedKeys(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbUpdateMapper : ElementMapper<DbUpdateConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbUpdateConfig {
        val metadata = element.dbMetadata(ctx)
        return DbUpdateConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            inputParameters = element.dbInputParameters(),
            parameterTypes = element.dbParameterTypes(),
            settings = element.dbSettings(),
            generatedKeys = element.dbGeneratedKeys(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbDeleteMapper : ElementMapper<DbDeleteConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbDeleteConfig {
        val metadata = element.dbMetadata(ctx)
        return DbDeleteConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            inputParameters = element.dbInputParameters(),
            parameterTypes = element.dbParameterTypes(),
            settings = element.dbSettings(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbBulkInsertMapper : ElementMapper<DbBulkInsertConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbBulkInsertConfig {
        val metadata = element.dbMetadata(ctx)
        return DbBulkInsertConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            batchParameters = element.dbBatchParameters(),
            parameterTypes = element.dbParameterTypes(),
            settings = element.dbSettings(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbBulkUpdateMapper : ElementMapper<DbBulkUpdateConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbBulkUpdateConfig {
        val metadata = element.dbMetadata(ctx)
        return DbBulkUpdateConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            batchParameters = element.dbBatchParameters(),
            parameterTypes = element.dbParameterTypes(),
            settings = element.dbSettings(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbBulkDeleteMapper : ElementMapper<DbBulkDeleteConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbBulkDeleteConfig {
        val metadata = element.dbMetadata(ctx)
        return DbBulkDeleteConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            batchParameters = element.dbBatchParameters(),
            parameterTypes = element.dbParameterTypes(),
            settings = element.dbSettings(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbExecuteDdlMapper : ElementMapper<DbExecuteDdlConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbExecuteDdlConfig {
        val metadata = element.dbMetadata(ctx)
        return DbExecuteDdlConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            settings = element.dbSettings(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbExecuteScriptMapper : ElementMapper<DbExecuteScriptConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbExecuteScriptConfig {
        element.rejectUnsupportedAttributes(
            "execute-script",
            setOf("fetchSize", "maxRows", "useColumnNumbers"),
        )
        val metadata = element.dbMetadata(ctx)
        val sql =
            element.descendants()
                .firstOrNull { it.tag == "sql" }
                ?.textContent
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: element.attrs["sql"]?.takeIf(String::isNotBlank)
        val scriptPath =
            element.attrs["file"]
                ?: element.attrs["path"]
                ?: element.attrs["scriptPath"]
        return DbExecuteScriptConfig(
            configRef = element.dbConfigRef(),
            sql = sql,
            scriptPath = scriptPath,
            settings = element.dbSettings(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbStoredProcedureMapper : ElementMapper<DbStoredProcedureConfig> {
    override fun map(element: RawElement, ctx: MappingContext): DbStoredProcedureConfig {
        val metadata = element.dbMetadata(ctx)
        val inOut = element.dbInOutParameters()
        val outputParameters =
            element.descendants()
                .filter { it.tag == "output-parameter" }
                .map { output ->
                    val name = output.attrs["key"] ?: output.attrs["name"]
                        ?: throw IllegalArgumentException("db:output-parameter requires key")
                    val type = output.attrs["type"] ?: output.attrs["jdbcType"]
                        ?: throw IllegalArgumentException("db:output-parameter '$name' requires type")
                    DbOutputParameter(
                        name,
                        dev.museframework.db.api.model.DbParameterType(
                            parseJdbcType(type),
                            output.attrs["customType"],
                            output.attrs["scale"]?.toInt(),
                        ),
                    )
                }.toList()
        return DbStoredProcedureConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbSql(),
            inputParameters = element.dbInputParameters(),
            inOutParameters = inOut.source,
            outputParameters = outputParameters,
            parameterTypes = inOut.types + element.dbParameterTypes(),
            settings = element.dbSettings(),
            targetConfig = element.dbTarget(),
            docName = metadata.docName,
            docId = metadata.docId,
            sourceFile = metadata.sourceFile,
            sourceLine = metadata.sourceLine,
        )
    }
}

object DbSelectCodeEmitter : ElementCodeEmitter<DbSelectConfig> {
    override fun emit(element: DbSelectConfig, ctx: CodegenContext): String =
        emitOperation("dbSelect", element, ctx) {
            emitParameterized(element, ctx)
            if (element.streamingStrategy != StreamingStrategy.RepeatableInMemory()) {
                ctx.addImport("dev.museframework.engine.api.streaming.StreamingStrategy")
                appendLine("    streamingStrategy = ${element.streamingStrategy.kotlinCode()}")
            }
        }
}

object DbQuerySingleCodeEmitter : ElementCodeEmitter<DbQuerySingleConfig> {
    override fun emit(element: DbQuerySingleConfig, ctx: CodegenContext): String =
        emitOperation("dbQuerySingle", element, ctx) { emitParameterized(element, ctx) }
}

object DbInsertCodeEmitter : ElementCodeEmitter<DbInsertConfig> {
    override fun emit(element: DbInsertConfig, ctx: CodegenContext): String =
        emitOperation("dbInsert", element, ctx) {
            emitParameterized(element, ctx)
            if (element.generatedKeys.enabled) appendLine("    autoGenerateKeys = true")
            element.generatedKeys.columnNames.forEach { appendLine("    generatedKeyColumn(${it.kotlinLiteral()})") }
            element.generatedKeys.columnIndexes.forEach { appendLine("    generatedKeyColumn($it)") }
        }
}

object DbUpdateCodeEmitter : ElementCodeEmitter<DbUpdateConfig> {
    override fun emit(element: DbUpdateConfig, ctx: CodegenContext): String =
        emitOperation("dbUpdate", element, ctx) {
            emitParameterized(element, ctx)
            if (element.generatedKeys.enabled) appendLine("    autoGenerateKeys = true")
            element.generatedKeys.columnNames.forEach { appendLine("    generatedKeyColumn(${it.kotlinLiteral()})") }
            element.generatedKeys.columnIndexes.forEach { appendLine("    generatedKeyColumn($it)") }
        }
}

object DbDeleteCodeEmitter : ElementCodeEmitter<DbDeleteConfig> {
    override fun emit(element: DbDeleteConfig, ctx: CodegenContext): String =
        emitOperation("dbDelete", element, ctx) { emitParameterized(element, ctx) }
}

object DbBulkInsertCodeEmitter : ElementCodeEmitter<DbBulkInsertConfig> {
    override fun emit(element: DbBulkInsertConfig, ctx: CodegenContext): String =
        emitBulkOperation("dbBulkInsert", element, ctx)
}

object DbBulkUpdateCodeEmitter : ElementCodeEmitter<DbBulkUpdateConfig> {
    override fun emit(element: DbBulkUpdateConfig, ctx: CodegenContext): String =
        emitBulkOperation("dbBulkUpdate", element, ctx)
}

object DbBulkDeleteCodeEmitter : ElementCodeEmitter<DbBulkDeleteConfig> {
    override fun emit(element: DbBulkDeleteConfig, ctx: CodegenContext): String =
        emitBulkOperation("dbBulkDelete", element, ctx)
}

object DbExecuteDdlCodeEmitter : ElementCodeEmitter<DbExecuteDdlConfig> {
    override fun emit(element: DbExecuteDdlConfig, ctx: CodegenContext): String =
        emitOperation("dbExecuteDdl", element, ctx) {
            appendLine("    sql = ${element.sql.kotlinLiteral()}")
            ctx.addSettingsImports(element.settings)
            emitSettings(element.settings)
        }
}

object DbExecuteScriptCodeEmitter : ElementCodeEmitter<DbExecuteScriptConfig> {
    override fun emit(element: DbExecuteScriptConfig, ctx: CodegenContext): String =
        emitOperation("dbExecuteScript", element, ctx) {
            element.sql?.let { appendLine("    sql = ${it.kotlinLiteral()}") }
            element.scriptPath?.let { appendLine("    scriptPath = ${it.kotlinLiteral()}") }
            ctx.addSettingsImports(element.settings)
            emitSettings(element.settings, settingNames = setOf("queryTimeout"))
        }
}

object DbStoredProcedureCodeEmitter : ElementCodeEmitter<DbStoredProcedureConfig> {
    override fun emit(element: DbStoredProcedureConfig, ctx: CodegenContext): String =
        emitOperation("dbStoredProcedure", element, ctx) {
            emitParameterized(element, ctx)
            when (val source = element.inOutParameters) {
                is DbParameterSource.Expression -> appendLine("    inOutParameters(${source.expression.kotlinLiteral()})")
                is DbParameterSource.Values -> {
                    val entries = source.values.entries.joinToString { (name, value) ->
                        "${name.kotlinLiteral()} to ${value.toString().kotlinLiteral()}"
                    }
                    if (entries.isNotEmpty()) appendLine("    inOutParameters(mapOf($entries))")
                }
            }
            if (element.outputParameters.isNotEmpty()) ctx.addImport("java.sql.JDBCType")
            element.outputParameters.forEach { output ->
                append("    outputParameter(${output.name.kotlinLiteral()}, JDBCType.${output.type.jdbcType}")
                output.type.typeName?.let { append(", typeName = ${it.kotlinLiteral()}") }
                output.type.scale?.let { append(", scale = $it") }
                appendLine(")")
            }
        }
}

private fun emitBulkOperation(
    functionName: String,
    element: DbBulkOperationConfig,
    ctx: CodegenContext,
): String =
    emitOperation(functionName, element, ctx) {
        appendLine("    sql = ${element.sql.kotlinLiteral()}")
        emitBatchParameters(element.batchParameters)
        emitParameterTypes(element.parameterTypes, ctx)
        ctx.addSettingsImports(element.settings)
        emitSettings(element.settings)
    }

private fun StringBuilder.emitParameterized(
    element: DbParameterizedOperationConfig,
    ctx: CodegenContext,
) {
    appendLine("    sql = ${element.sql.kotlinLiteral()}")
    emitInputParameters(element.inputParameters)
    emitParameterTypes(element.parameterTypes, ctx)
    ctx.addSettingsImports(element.settings)
    emitSettings(element.settings)
}

private fun <T : DbOperationConfig> emitOperation(
    functionName: String,
    element: T,
    ctx: CodegenContext,
    body: StringBuilder.() -> Unit,
): String {
    ctx.addImport("dev.museframework.db.api.dsl.$functionName")
    ctx.addImport("dev.museframework.db.api.config.DbConfigRef")
    return buildString {
        appendLine("$functionName(DbConfigRef.ByName(${element.configRef.name().kotlinLiteral()})) {")
        body()
        if (element.docName.isNotBlank()) appendLine("    docName = ${element.docName.kotlinLiteral()}")
        emitTarget(element.targetConfig)
        append("}")
    }
}

private fun StreamingStrategy.kotlinCode(): String =
    when (this) {
        is StreamingStrategy.RepeatableInMemory ->
            "StreamingStrategy.RepeatableInMemory(" +
                "initialBufferSizeBytes = $initialBufferSizeBytes, " +
                "bufferSizeIncrementBytes = $bufferSizeIncrementBytes, " +
                "maxBufferSizeBytes = $maxBufferSizeBytes, " +
                "initialObjectBufferSize = $initialObjectBufferSize, " +
                "objectBufferSizeIncrement = $objectBufferSizeIncrement, " +
                "maxObjectBufferSize = $maxObjectBufferSize)"
        is StreamingStrategy.RepeatableFileStore ->
            "StreamingStrategy.RepeatableFileStore(" +
                "inMemorySizeBytes = $inMemorySizeBytes, " +
                "tempDirectory = ${tempDirectory?.kotlinLiteral() ?: "null"}, " +
                "deleteFilesOnClose = $deleteFilesOnClose)"
        StreamingStrategy.NonRepeatable -> "StreamingStrategy.NonRepeatable"
    }

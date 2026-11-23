package dev.museframework.babelfish.mappers.db

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.db.api.model.DbGeneratedKeys
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
        val scriptPath =
            element.attrs["file"]
                ?: element.attrs["path"]
                ?: element.attrs["scriptPath"]
        return DbExecuteScriptConfig(
            configRef = element.dbConfigRef(),
            sql = element.dbOptionalSql(),
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
    override fun emit(element: DbSelectConfig, ctx: CodegenContext): CodeBlock =
        emitOperation("dbSelect", element) {
            emitParameterized(element)
            if (element.streamingStrategy != StreamingStrategy.RepeatableInMemory()) {
                addStatement("streamingStrategy = %L", element.streamingStrategy.kotlinCode())
            }
        }
}

object DbQuerySingleCodeEmitter : ElementCodeEmitter<DbQuerySingleConfig> {
    override fun emit(element: DbQuerySingleConfig, ctx: CodegenContext): CodeBlock =
        emitOperation("dbQuerySingle", element) { emitParameterized(element) }
}

object DbInsertCodeEmitter : ElementCodeEmitter<DbInsertConfig> {
    override fun emit(element: DbInsertConfig, ctx: CodegenContext): CodeBlock =
        emitOperation("dbInsert", element) {
            emitParameterized(element)
            emitGeneratedKeys(element.generatedKeys)
        }
}

object DbUpdateCodeEmitter : ElementCodeEmitter<DbUpdateConfig> {
    override fun emit(element: DbUpdateConfig, ctx: CodegenContext): CodeBlock =
        emitOperation("dbUpdate", element) {
            emitParameterized(element)
            emitGeneratedKeys(element.generatedKeys)
        }
}

object DbDeleteCodeEmitter : ElementCodeEmitter<DbDeleteConfig> {
    override fun emit(element: DbDeleteConfig, ctx: CodegenContext): CodeBlock =
        emitOperation("dbDelete", element) { emitParameterized(element) }
}

object DbBulkInsertCodeEmitter : ElementCodeEmitter<DbBulkInsertConfig> {
    override fun emit(element: DbBulkInsertConfig, ctx: CodegenContext): CodeBlock =
        emitBulkOperation("dbBulkInsert", element)
}

object DbBulkUpdateCodeEmitter : ElementCodeEmitter<DbBulkUpdateConfig> {
    override fun emit(element: DbBulkUpdateConfig, ctx: CodegenContext): CodeBlock =
        emitBulkOperation("dbBulkUpdate", element)
}

object DbBulkDeleteCodeEmitter : ElementCodeEmitter<DbBulkDeleteConfig> {
    override fun emit(element: DbBulkDeleteConfig, ctx: CodegenContext): CodeBlock =
        emitBulkOperation("dbBulkDelete", element)
}

object DbExecuteDdlCodeEmitter : ElementCodeEmitter<DbExecuteDdlConfig> {
    override fun emit(element: DbExecuteDdlConfig, ctx: CodegenContext): CodeBlock =
        emitOperation("dbExecuteDdl", element) {
            addStatement("sql = %L", kotlinStringLiteral(element.sql))
            emitSettings(element.settings)
        }
}

object DbExecuteScriptCodeEmitter : ElementCodeEmitter<DbExecuteScriptConfig> {
    override fun emit(element: DbExecuteScriptConfig, ctx: CodegenContext): CodeBlock =
        emitOperation("dbExecuteScript", element) {
            element.sql?.let { addStatement("sql = %L", kotlinStringLiteral(it)) }
            element.scriptPath?.let { addStatement("scriptPath = %L", kotlinStringLiteral(it)) }
            emitTimeout(element.settings)
        }
}

object DbStoredProcedureCodeEmitter : ElementCodeEmitter<DbStoredProcedureConfig> {
    override fun emit(element: DbStoredProcedureConfig, ctx: CodegenContext): CodeBlock =
        emitOperation("dbStoredProcedure", element) {
            emitParameterized(element)
            when (val source = element.inOutParameters) {
                is DbParameterSource.Expression -> addStatement("inOutParameters(%L)", kotlinStringLiteral(source.expression))
                is DbParameterSource.Values -> if (source.values.isNotEmpty()) {
                    addStatement("inOutParameters(%L)", source.values.parameterMap())
                }
            }
            element.outputParameters.forEach { output -> emitTypedParameter("outputParameter", output.name, output.type) }
        }
}

private fun emitBulkOperation(functionName: String, element: DbBulkOperationConfig): CodeBlock =
    emitOperation(functionName, element) {
        addStatement("sql = %L", kotlinStringLiteral(element.sql))
        emitBatchParameters(element.batchParameters)
        element.parameterTypes.forEach { (name, type) -> emitTypedParameter("parameterType", name, type) }
        emitSettings(element.settings)
    }

private fun CodeBlock.Builder.emitParameterized(element: DbParameterizedOperationConfig) {
    addStatement("sql = %L", kotlinStringLiteral(element.sql))
    emitInputParameters(element.inputParameters)
    element.parameterTypes.forEach { (name, type) -> emitTypedParameter("parameterType", name, type) }
    emitSettings(element.settings)
}

private fun CodeBlock.Builder.emitGeneratedKeys(keys: DbGeneratedKeys) {
    if (keys.enabled) addStatement("autoGenerateKeys = true")
    keys.columnNames.forEach { addStatement("generatedKeyColumn(%L)", kotlinStringLiteral(it)) }
    keys.columnIndexes.forEach { addStatement("generatedKeyColumn(%L)", it) }
}

private fun emitOperation(
    functionName: String,
    element: DbOperationConfig,
    body: CodeBlock.Builder.() -> Unit,
): CodeBlock = buildCodeBlock {
    beginControlFlow("%M(%L)", dbDsl(functionName), element.configRef.code())
    body()
    if (element.docName.isNotBlank()) addStatement("docName = %L", kotlinStringLiteral(element.docName))
    emitTarget(element.targetConfig)
    endControlFlow()
}

private fun StreamingStrategy.kotlinCode(): CodeBlock =
    when (this) {
        is StreamingStrategy.RepeatableInMemory -> CodeBlock.of(
            "%T.RepeatableInMemory(initialBufferSizeBytes = %L, bufferSizeIncrementBytes = %L, maxBufferSizeBytes = %L, " +
                "initialObjectBufferSize = %L, objectBufferSizeIncrement = %L, maxObjectBufferSize = %L)",
            StreamingStrategy::class, initialBufferSizeBytes, bufferSizeIncrementBytes, maxBufferSizeBytes,
            initialObjectBufferSize, objectBufferSizeIncrement, maxObjectBufferSize,
        )
        is StreamingStrategy.RepeatableFileStore -> CodeBlock.of(
            "%T.RepeatableFileStore(inMemorySizeBytes = %L, tempDirectory = %L, deleteFilesOnClose = %L)",
            StreamingStrategy::class, inMemorySizeBytes, tempDirectory?.let(::kotlinStringLiteral), deleteFilesOnClose,
        )
        StreamingStrategy.NonRepeatable -> CodeBlock.of("%T.NonRepeatable", StreamingStrategy::class)
    }

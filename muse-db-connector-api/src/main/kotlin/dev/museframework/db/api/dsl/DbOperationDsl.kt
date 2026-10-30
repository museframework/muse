package dev.museframework.db.api.dsl

import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.dsl.api.dsl.PipelineOwner

fun PipelineOwner.dbSelect(config: DbConfig, block: DbSelectBuilder.() -> Unit) =
    dbSelect(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbSelect(configRef: DbConfigRef, block: DbSelectBuilder.() -> Unit) {
    processor(DbSelectBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbQuerySingle(config: DbConfig, block: DbQuerySingleBuilder.() -> Unit) =
    dbQuerySingle(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbQuerySingle(configRef: DbConfigRef, block: DbQuerySingleBuilder.() -> Unit) {
    processor(DbQuerySingleBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbInsert(config: DbConfig, block: DbInsertBuilder.() -> Unit) =
    dbInsert(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbInsert(configRef: DbConfigRef, block: DbInsertBuilder.() -> Unit) {
    processor(DbInsertBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbUpdate(config: DbConfig, block: DbUpdateBuilder.() -> Unit) =
    dbUpdate(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbUpdate(configRef: DbConfigRef, block: DbUpdateBuilder.() -> Unit) {
    processor(DbUpdateBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbDelete(config: DbConfig, block: DbDeleteBuilder.() -> Unit) =
    dbDelete(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbDelete(configRef: DbConfigRef, block: DbDeleteBuilder.() -> Unit) {
    processor(DbDeleteBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbBulkInsert(config: DbConfig, block: DbBulkInsertBuilder.() -> Unit) =
    dbBulkInsert(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbBulkInsert(configRef: DbConfigRef, block: DbBulkInsertBuilder.() -> Unit) {
    processor(DbBulkInsertBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbBulkUpdate(config: DbConfig, block: DbBulkUpdateBuilder.() -> Unit) =
    dbBulkUpdate(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbBulkUpdate(configRef: DbConfigRef, block: DbBulkUpdateBuilder.() -> Unit) {
    processor(DbBulkUpdateBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbBulkDelete(config: DbConfig, block: DbBulkDeleteBuilder.() -> Unit) =
    dbBulkDelete(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbBulkDelete(configRef: DbConfigRef, block: DbBulkDeleteBuilder.() -> Unit) {
    processor(DbBulkDeleteBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbExecuteDdl(config: DbConfig, block: DbExecuteDdlBuilder.() -> Unit) =
    dbExecuteDdl(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbExecuteDdl(configRef: DbConfigRef, block: DbExecuteDdlBuilder.() -> Unit) {
    processor(DbExecuteDdlBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbExecuteScript(config: DbConfig, block: DbExecuteScriptBuilder.() -> Unit) =
    dbExecuteScript(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbExecuteScript(configRef: DbConfigRef, block: DbExecuteScriptBuilder.() -> Unit) {
    processor(DbExecuteScriptBuilder(configRef).apply(block).build())
}

fun PipelineOwner.dbStoredProcedure(config: DbConfig, block: DbStoredProcedureBuilder.() -> Unit) =
    dbStoredProcedure(DbConfigRef.ByReference(config), block)

fun PipelineOwner.dbStoredProcedure(configRef: DbConfigRef, block: DbStoredProcedureBuilder.() -> Unit) {
    processor(DbStoredProcedureBuilder(configRef).apply(block).build())
}

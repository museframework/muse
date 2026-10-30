package dev.museframework.babelfish.mappers.db

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.db.api.config.DbConfig
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
import dev.museframework.db.api.source.DbRowListenerConfig

object DbMappersRegistrar {
    fun register(registry: BabelfishRegistry) {
        registry.registerConfig("db", "config", DbConfigMapper)
        registry.registerElement("db", "select", DbSelectMapper)
        registry.registerElement("db", "query-single", DbQuerySingleMapper)
        registry.registerElement("db", "insert", DbInsertMapper)
        registry.registerElement("db", "update", DbUpdateMapper)
        registry.registerElement("db", "delete", DbDeleteMapper)
        registry.registerElement("db", "bulk-insert", DbBulkInsertMapper)
        registry.registerElement("db", "bulk-update", DbBulkUpdateMapper)
        registry.registerElement("db", "bulk-delete", DbBulkDeleteMapper)
        registry.registerElement("db", "execute-ddl", DbExecuteDdlMapper)
        registry.registerElement("db", "execute-script", DbExecuteScriptMapper)
        registry.registerElement("db", "stored-procedure", DbStoredProcedureMapper)
        registry.registerSource("db", "listener", DbRowListenerMapper)
    }

    fun registerEmitters(emitter: KotlinCodeEmitter) {
        emitter.registerConfigEmitter(DbConfig::class, DbConfigCodeEmitter)
        emitter.registerElementEmitter(DbSelectConfig::class, DbSelectCodeEmitter)
        emitter.registerElementEmitter(DbQuerySingleConfig::class, DbQuerySingleCodeEmitter)
        emitter.registerElementEmitter(DbInsertConfig::class, DbInsertCodeEmitter)
        emitter.registerElementEmitter(DbUpdateConfig::class, DbUpdateCodeEmitter)
        emitter.registerElementEmitter(DbDeleteConfig::class, DbDeleteCodeEmitter)
        emitter.registerElementEmitter(DbBulkInsertConfig::class, DbBulkInsertCodeEmitter)
        emitter.registerElementEmitter(DbBulkUpdateConfig::class, DbBulkUpdateCodeEmitter)
        emitter.registerElementEmitter(DbBulkDeleteConfig::class, DbBulkDeleteCodeEmitter)
        emitter.registerElementEmitter(DbExecuteDdlConfig::class, DbExecuteDdlCodeEmitter)
        emitter.registerElementEmitter(DbExecuteScriptConfig::class, DbExecuteScriptCodeEmitter)
        emitter.registerElementEmitter(DbStoredProcedureConfig::class, DbStoredProcedureCodeEmitter)
        emitter.registerSourceEmitter(DbRowListenerConfig::class, DbRowListenerCodeEmitter)
    }
}

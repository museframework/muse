package dev.museframework.connectors.db

import dev.museframework.connectors.db.connection.DbConnectionFactory
import dev.museframework.connectors.db.processor.DbOperationMuseProcessor
import dev.museframework.connectors.db.source.dbRowListenerSourceBuilder
import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.operation.DbBulkDeleteConfig
import dev.museframework.db.api.operation.DbBulkInsertConfig
import dev.museframework.db.api.operation.DbBulkUpdateConfig
import dev.museframework.db.api.operation.DbDeleteConfig
import dev.museframework.db.api.operation.DbExecuteDdlConfig
import dev.museframework.db.api.operation.DbExecuteScriptConfig
import dev.museframework.db.api.operation.DbInsertConfig
import dev.museframework.db.api.operation.DbOperationConfig
import dev.museframework.db.api.operation.DbQuerySingleConfig
import dev.museframework.db.api.operation.DbSelectConfig
import dev.museframework.db.api.operation.DbStoredProcedureConfig
import dev.museframework.db.api.operation.DbUpdateConfig
import dev.museframework.db.api.source.DbRowListenerConfig
import dev.museframework.engine.api.spi.MuseComponentFactory
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.engine.api.spi.ProcessorBuilder

class DbConnectorRegistrar : MuseComponentRegistrar {
    override fun register(factory: MuseComponentFactory) {
        factory.registerConfig(DbConfig::class) { config, context ->
            if (config.validateConnectivity) DbConnectionFactory(config, context.properties).validate()
        }
        factory.registerProcessor(DbSelectConfig::class, operationBuilder())
        factory.registerProcessor(DbQuerySingleConfig::class, operationBuilder())
        factory.registerProcessor(DbInsertConfig::class, operationBuilder())
        factory.registerProcessor(DbUpdateConfig::class, operationBuilder())
        factory.registerProcessor(DbDeleteConfig::class, operationBuilder())
        factory.registerProcessor(DbBulkInsertConfig::class, operationBuilder())
        factory.registerProcessor(DbBulkUpdateConfig::class, operationBuilder())
        factory.registerProcessor(DbBulkDeleteConfig::class, operationBuilder())
        factory.registerProcessor(DbExecuteDdlConfig::class, operationBuilder())
        factory.registerProcessor(DbExecuteScriptConfig::class, operationBuilder())
        factory.registerProcessor(DbStoredProcedureConfig::class, operationBuilder())
        factory.registerSource(DbRowListenerConfig::class, dbRowListenerSourceBuilder)
    }

    private fun <T : DbOperationConfig> operationBuilder(): ProcessorBuilder<T> =
        ProcessorBuilder { component, context ->
            val config = resolveConfig(component.configRef, context)
            DbOperationMuseProcessor(
                component = component,
                connectionFactory = DbConnectionFactory(config, context.properties),
                expressionEvaluator = context.expressionEvaluator,
            )
        }
}

internal fun resolveConfig(
    reference: DbConfigRef,
    context: MuseContext,
): DbConfig =
    when (reference) {
        is DbConfigRef.ByReference -> reference.config
        is DbConfigRef.ByName -> {
            val matches = context.configs.values.filterIsInstance<DbConfig>().filter { it.name == reference.name }
            when (matches.size) {
                1 -> matches.single()
                0 -> throw IllegalStateException("Database configuration '${reference.name}' was not found")
                else -> throw IllegalStateException("Database configuration name '${reference.name}' is ambiguous")
            }
        }
    }

package dev.museframework.babelfish.mappers.db

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.config.GenericDbConnectionConfig
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
import dev.museframework.dsl.api.model.flow.MuseFlow
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DbTranslationIT {
    @Test
    fun `translates and emits every database provider component category`() {
        val provider = DbMapperProvider()
        val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
        val result =
            MuseBabelfish.translateXmlToMuseKotlinDsl(
                Path.of("src/test/resources/mule/db/database-components.xml"),
                registry,
            )

        val config = assertIs<DbConfig>(result.usedConfigs.getValue("database-config"))
        val connection = assertIs<GenericDbConnectionConfig>(config.connection)
        assertEquals("jdbc:postgresql://localhost/orders", connection.url)
        assertEquals("${'$'}{db.password}", connection.password)
        assertEquals("require", connection.connectionProperties["sslmode"])

        val operationsFlow = assertIs<MuseFlow.Flow>(result.flows.first { it.name == "database-operations" })
        assertEquals(
            listOf(
                DbSelectConfig::class,
                DbQuerySingleConfig::class,
                DbInsertConfig::class,
                DbUpdateConfig::class,
                DbDeleteConfig::class,
                DbBulkInsertConfig::class,
                DbBulkUpdateConfig::class,
                DbBulkDeleteConfig::class,
                DbExecuteDdlConfig::class,
                DbExecuteScriptConfig::class,
                DbStoredProcedureConfig::class,
            ),
            operationsFlow.pipeline.map { it::class },
        )
        val select = assertIs<DbSelectConfig>(operationsFlow.pipeline.first())
        assertEquals(DbConfigRef.ByName("database-config"), select.configRef)
        assertEquals(25, select.settings.fetchSize)

        val listenerFlow = assertIs<MuseFlow.Flow>(result.flows.first { it.name == "database-listener" })
        val listener = assertIs<DbRowListenerConfig>(listenerFlow.source)
        assertEquals("outbox", listener.table)
        assertEquals("id", listener.watermarkColumn)
        assertEquals("5", listener.schedulingStrategy.frequency)

        val code =
            KotlinCodeEmitter()
                .apply { provider.registerEmitters(this) }
                .emit(result, "dev.museframework.babelfish.generated")
        assertContains(code, "import dev.museframework.db.api.dsl.dbConfig")
        assertContains(code, "import dev.museframework.db.api.dsl.dbSelect")
        assertContains(code, "import dev.museframework.db.api.dsl.dbStoredProcedure")
        assertContains(code, "import dev.museframework.db.api.dsl.dbListener")
        assertContains(code, "dbSelect(DbConfigRef.ByName(\"database-config\"))")
        assertContains(code, "dbListener(\"database-config\")")
        assertFalse(code.contains("dev.museframework.connectors.db"))
    }
}

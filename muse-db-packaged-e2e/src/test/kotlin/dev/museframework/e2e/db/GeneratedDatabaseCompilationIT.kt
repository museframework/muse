package dev.museframework.e2e.db

import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.model.DbBatchParameterSource
import dev.museframework.db.api.model.DbGeneratedKeys
import dev.museframework.db.api.model.DbOutputParameter
import dev.museframework.db.api.model.DbParameterSource
import dev.museframework.db.api.model.DbParameterType
import dev.museframework.db.api.model.DbQuerySettings
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
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.e2e.db.generated.DatabaseConfig
import dev.museframework.engine.api.streaming.StreamingStrategy
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.sql.JDBCType
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratedDatabaseCompilationIT {
    @Test
    fun `generated database configuration and application classes load from compiled bytecode`() {
        val databaseConfig = loadGeneratedClass("DatabaseConfig")
        assertTrue(
            MuseConfigDefinition::class.java.isAssignableFrom(databaseConfig),
            "${databaseConfig.name} must extend MuseConfigDefinition",
        )
        assertNotNull(databaseConfig.getDeclaredConstructor().newInstance())

        val applicationClass = loadGeneratedClass("MuseDbPackagedE2eApplicationKt")
        val mainMethod = applicationClass.getDeclaredMethod("main", Array<String>::class.java)
        assertTrue(Modifier.isPublic(mainMethod.modifiers))
        assertTrue(Modifier.isStatic(mainMethod.modifiers))
        assertEquals(Void.TYPE, mainMethod.returnType)
    }

    @Test
    fun `compiled database config and operation preserve source XML IDs`() {
        val generated = DatabaseConfig()
        val select = assertIs<DbSelectConfig>(generated.databaseFidelity.pipeline.first())

        assertEquals("packaged-database-config-original", generated.packagedDatabase.docId)
        assertEquals("packaged-database-select-original", select.docId)
    }

    @Test
    fun `compiled database operations preserve SQL optional targets and typed parameters`() {
        val operations = DatabaseConfig().databaseFidelity.pipeline
        assertEquals(12, operations.size)
        val select = assertIs<DbSelectConfig>(operations[0])
        assertEquals("  SELECT '\"\$price\"' FROM items\r\nWHERE id = :id\r\n\r\n", select.sql)
        assertEquals(DbConfigRef.ByName("packaged-database"), select.configRef)
        assertEquals(DbParameterSource.Expression("#[{ id: vars.id }]"), select.inputParameters)
        assertEquals(mapOf("id" to DbParameterType(JDBCType.DECIMAL, "amount", 2)), select.parameterTypes)
        assertEquals(TargetConfig("rows", "#[payload[0]]"), select.targetConfig)
        assertEquals(DbQuerySettings(Duration.ofMillis(125), 4, 8, true), select.settings)
        assertEquals(
            StreamingStrategy.RepeatableInMemory(initialObjectBufferSize = 3, objectBufferSizeIncrement = 2, maxObjectBufferSize = 9),
            select.streamingStrategy,
        )

        val single = assertIs<DbQuerySingleConfig>(operations[1])
        assertEquals("SELECT 1", single.sql)
        assertEquals(DbParameterSource.EMPTY, single.inputParameters)
        assertEquals(emptyMap(), single.parameterTypes)
        assertNull(single.targetConfig)
        assertEquals(DbGeneratedKeys(true, listOf("id")), assertIs<DbInsertConfig>(operations[2]).generatedKeys)
        assertEquals(DbGeneratedKeys(true, columnIndexes = listOf(1)), assertIs<DbUpdateConfig>(operations[3]).generatedKeys)
        assertEquals("DELETE FROM t", assertIs<DbDeleteConfig>(operations[4]).sql)

        val bulk = assertIs<DbBulkInsertConfig>(operations[5])
        assertEquals(DbBatchParameterSource.Expression("#[vars.batch]"), bulk.batchParameters)
        assertEquals(mapOf("id" to DbParameterType(JDBCType.INTEGER)), bulk.parameterTypes)
        assertEquals(DbBatchParameterSource.PAYLOAD, assertIs<DbBulkUpdateConfig>(operations[6]).batchParameters)
        assertEquals("DELETE FROM t WHERE id = :id", assertIs<DbBulkDeleteConfig>(operations[7]).sql)
        assertEquals("CREATE TABLE t (id INT)", assertIs<DbExecuteDdlConfig>(operations[8]).sql)

        val inline = assertIs<DbExecuteScriptConfig>(operations[9])
        assertEquals("SELECT 1;\r\n\r\n", inline.sql)
        assertNull(inline.scriptPath)
        val resource = assertIs<DbExecuteScriptConfig>(operations[10])
        assertEquals("sql/\$script.sql", resource.scriptPath)
        assertNull(resource.sql)

        val procedure = assertIs<DbStoredProcedureConfig>(operations[11])
        assertEquals(DbParameterSource.Values(mapOf("amount" to "3.00", "empty" to null)), procedure.inOutParameters)
        assertEquals(mapOf("amount" to DbParameterType(JDBCType.DECIMAL, "amount", 2)), procedure.parameterTypes)
        assertEquals(listOf(DbOutputParameter("id", DbParameterType(JDBCType.NUMERIC, scale = 4))), procedure.outputParameters)
    }

    private fun loadGeneratedClass(simpleName: String): Class<*> =
        Class.forName("$GENERATED_PACKAGE.$simpleName")

    private companion object {
        const val GENERATED_PACKAGE = "dev.museframework.e2e.db.generated"
    }
}

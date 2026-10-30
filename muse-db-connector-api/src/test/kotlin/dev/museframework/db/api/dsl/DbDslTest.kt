package dev.museframework.db.api.dsl

import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.config.GenericDbConnectionConfig
import dev.museframework.db.api.model.DbBatchParameterSource
import dev.museframework.db.api.model.DbParameterSource
import dev.museframework.db.api.operation.DbBulkInsertConfig
import dev.museframework.db.api.operation.DbInsertConfig
import dev.museframework.db.api.operation.DbStoredProcedureConfig
import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import org.junit.jupiter.api.Test
import java.sql.JDBCType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DbDslTest {
    @Test
    fun `generic configuration keeps credentials redacted and typed`() {
        val config =
            dbConfig("orders-db") {
                validateConnectivity = false
                generic {
                    url = "jdbc:postgresql://localhost/orders"
                    driverClassName = "org.postgresql.Driver"
                    user = "muse"
                    password = "secret"
                    connectionProperty("sslmode", "require")
                    connectionProperty("accessToken", "token-secret")
                }
            }

        val connection = assertIs<GenericDbConnectionConfig>(config.connection)
        assertEquals("orders-db", config.name)
        assertEquals("require", connection.connectionProperties["sslmode"])
        assertFalse(connection.toString().contains("secret"))
        assertFalse(connection.toString().contains("token-secret"))
        assertTrue(connection.toString().contains("<redacted>"))
    }

    @Test
    fun `DML builder retains named parameters types generated keys and target`() {
        val owner = RecordingPipelineOwner()
        val config = testConfig()

        owner.dbInsert(config) {
            sql = "INSERT INTO orders(name) VALUES (:name)"
            inputParameter("name", "#[payload]")
            parameterType("name", JDBCType.VARCHAR)
            autoGenerateKeys = true
            generatedKeyColumn("id")
            target = "insertResult"
        }

        val insert = assertIs<DbInsertConfig>(owner.processors.single())
        assertIs<DbConfigRef.ByReference>(insert.configRef)
        assertEquals(DbParameterSource.Values(mapOf("name" to "#[payload]")), insert.inputParameters)
        assertEquals(JDBCType.VARCHAR, insert.parameterTypes.getValue("name").jdbcType)
        assertEquals(listOf("id"), insert.generatedKeys.columnNames)
        assertEquals("insertResult", insert.targetConfig?.target)
    }

    @Test
    fun `bulk and stored procedure builders preserve their distinct parameter sources`() {
        val owner = RecordingPipelineOwner()
        val config = testConfig()

        owner.dbBulkInsert(config) {
            sql = "INSERT INTO orders(id, name) VALUES (:id, :name)"
            batchParameters(listOf(mapOf("id" to 1, "name" to "one")))
        }
        owner.dbStoredProcedure(config) {
            sql = "{ call update_order(:id, :status) }"
            inputParameter("id", 1)
            inOutParameters(mapOf("status" to "new"))
            outputParameter("status", JDBCType.VARCHAR)
        }

        val bulk = assertIs<DbBulkInsertConfig>(owner.processors[0])
        assertEquals(
            DbBatchParameterSource.Values(listOf(mapOf("id" to 1, "name" to "one"))),
            bulk.batchParameters,
        )
        val procedure = assertIs<DbStoredProcedureConfig>(owner.processors[1])
        assertEquals(DbParameterSource.Values(mapOf("status" to "new")), procedure.inOutParameters)
        assertEquals(JDBCType.VARCHAR, procedure.outputParameters.single().type.jdbcType)
    }

    private fun testConfig() =
        dbConfig("test") {
            validateConnectivity = false
            generic {
                url = "jdbc:test"
                driverClassName = "example.Driver"
            }
        }

    private class RecordingPipelineOwner : PipelineOwner {
        val processors = mutableListOf<Processor>()

        override fun processor(processor: Processor) {
            processors += processor
        }

        override fun router(router: Router) = error("Routers are not used by this test")
    }
}

package dev.museframework.connectors.db.execution

import dev.museframework.connectors.db.connection.DbConnectionFactory
import dev.museframework.db.api.config.DataSourceDbConnectionConfig
import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.operation.DbSelectConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Properties
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class JdbcOperationsTest {
    @Test
    fun `select failure closes the connection despite statement cleanup failure`() {
        val dataSource = mockk<DataSource>()
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()
        val evaluator = mockk<MuseExpressionEvaluator>()
        val queryFailure = SQLException("query failed", "42000")
        val statementCloseFailure = SQLException("statement close failed")
        val connectionCloseFailure = SQLException("connection close failed")
        every { dataSource.connection } returns connection
        every { connection.prepareStatement("SELECT 1") } returns statement
        every { evaluator.evaluate("SELECT 1", any()) } returns null
        every { statement.executeQuery() } throws queryFailure
        every { statement.close() } throws statementCloseFailure
        every { connection.close() } throws connectionCloseFailure
        val dbConfig = DbConfig("cleanup", DataSourceDbConnectionConfig(dataSource))
        val operations = JdbcOperations(DbConnectionFactory(dbConfig, Properties()), evaluator)

        val actual = assertFailsWith<SQLException> {
            operations.select(
                DbSelectConfig(DbConfigRef.ByReference(dbConfig), "SELECT 1"),
                MuseEvent(),
            )
        }

        verify(exactly = 1) { statement.close() }
        verify(exactly = 1) { connection.close() }
        assertSame(queryFailure, actual)
        assertEquals(listOf(statementCloseFailure, connectionCloseFailure), actual.suppressed.toList())
    }
}

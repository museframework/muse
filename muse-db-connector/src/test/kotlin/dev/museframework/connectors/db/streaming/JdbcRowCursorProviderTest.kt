package dev.museframework.connectors.db.streaming

import dev.museframework.engine.api.streaming.StreamingStrategy
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class JdbcRowCursorProviderTest {
    @Test
    fun `non-repeatable cursor remains exhausted and releases JDBC resources once`() {
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()
        val resultSet = mockk<ResultSet>()
        every { resultSet.next() } returns false
        every { resultSet.close() } just runs
        every { statement.close() } just runs
        every { connection.close() } just runs
        val provider =
            JdbcRowCursorProvider(
                JdbcRowSource(connection, statement, resultSet, useColumnNumbers = false),
                StreamingStrategy.NonRepeatable,
            )
        val cursor = provider.openCursor()

        assertFalse(cursor.hasNext())
        assertFalse(cursor.hasNext())

        verify(exactly = 1) { resultSet.close() }
        verify(exactly = 1) { statement.close() }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `unsupported file-store strategy is rejected before provider construction`() {
        assertFailsWith<UnsupportedOperationException> {
            JdbcRowCursorProvider.validateStrategy(StreamingStrategy.RepeatableFileStore())
        }
    }

    @Test
    fun `invalid in-memory buffer sizes are rejected before provider construction`() {
        assertFailsWith<IllegalArgumentException> {
            JdbcRowCursorProvider.validateStrategy(
                StreamingStrategy.RepeatableInMemory(initialObjectBufferSize = 0),
            )
        }
    }

    @Test
    fun `provider construction failure closes the JDBC source`() {
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()
        val resultSet = mockk<ResultSet>()
        every { resultSet.close() } just runs
        every { statement.close() } just runs
        every { connection.close() } just runs
        val source = JdbcRowSource(connection, statement, resultSet, useColumnNumbers = false)

        assertFailsWith<IllegalArgumentException> {
            try {
                JdbcRowCursorProvider(
                    source,
                    StreamingStrategy.RepeatableInMemory(initialObjectBufferSize = 0),
                )
            } catch (failure: Throwable) {
                runCatching(source::close)
                throw failure
            }
        }

        verify(exactly = 1) { resultSet.close() }
        verify(exactly = 1) { statement.close() }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `cursor read failure preserves cause and closes every JDBC resource`() {
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()
        val resultSet = mockk<ResultSet>()
        val readFailure = SQLException("read failed")
        val closeFailure = SQLException("result set close failed")
        every { resultSet.next() } throws readFailure
        every { resultSet.close() } throws closeFailure
        every { statement.close() } just runs
        every { connection.close() } just runs
        val provider =
            JdbcRowCursorProvider(
                JdbcRowSource(connection, statement, resultSet, useColumnNumbers = false),
                StreamingStrategy.RepeatableInMemory(),
            )
        val cursor = provider.openCursor()

        val actual = assertFailsWith<SQLException> { cursor.hasNext() }

        assertSame(readFailure, actual)
        assertSame(closeFailure, actual.suppressed.single())
        verify(exactly = 1) { resultSet.close() }
        verify(exactly = 1) { statement.close() }
        verify(exactly = 1) { connection.close() }
    }
}

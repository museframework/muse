package dev.museframework.e2e.composition

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class H2AuditDatabaseTest {
    @Test
    fun `real TCP JDBC connectivity and empty audit state`() {
        val directory = Files.createTempDirectory("composition-h2-")
        H2AuditDatabase.start(directory).use { database ->
            assertTrue(database.jdbcUrl.startsWith("jdbc:h2:tcp://127.0.0.1:"))
            assertEquals(emptyList(), database.rows())
        }
    }

    @Test
    fun `applicationConnectionCount detects retained application sessions`() {
        val directory = Files.createTempDirectory("composition-h2-connections-")
        H2AuditDatabase.start(directory).use { database ->
            val baseline = database.applicationConnectionCount()
            val held =
                java.sql.DriverManager.getConnection(database.jdbcUrl, database.user, database.password)
            try {
                assertEquals(baseline + 1, database.applicationConnectionCount())
            } finally {
                held.close()
            }
            assertEquals(baseline, database.applicationConnectionCount())
        }
    }

    @Test
    fun `rejectInserts blocks application inserts without blocking admin reads`() {
        val directory = Files.createTempDirectory("composition-h2-reject-")
        H2AuditDatabase.start(directory).use { database ->
            database.withApplicationInsert("sample.bin", 10L)
            assertEquals(listOf(AuditRow("sample.bin", 10L)), database.rows())
            database.removeRow("sample.bin")

            database.rejectInserts()
            assertFailsWith<Exception> {
                database.withApplicationInsert("blocked.bin", 1L)
            }
            assertEquals(emptyList(), database.rows())

            assertEquals(emptyList(), database.rows())
            database.allowInserts()
            database.withApplicationInsert("restored.bin", 2L)
            assertEquals(listOf(AuditRow("restored.bin", 2L)), database.rows())
        }
    }

    private fun H2AuditDatabase.withApplicationInsert(
        id: String,
        byteCount: Long,
    ) {
        java.sql.DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            connection.prepareStatement("INSERT INTO audit (id, byte_count) VALUES (?, ?)").use { statement ->
                statement.setString(1, id)
                statement.setLong(2, byteCount)
                statement.executeUpdate()
            }
        }
    }
}

package dev.museframework.e2e.composition

import org.h2.tools.Server
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

data class AuditRow(
    val id: String,
    val byteCount: Long,
)

class H2AuditDatabase private constructor(
    private val tcpServer: Server,
    private val adminJdbcUrl: String,
    private val adminUser: String,
    private val adminPassword: String,
    val jdbcUrl: String,
    val user: String,
    val password: String,
) : AutoCloseable {
    private val rejectConstraintName = "composition_audit_reject_inserts"

    @Volatile
    internal var closeFailureForTests: Throwable? = null

    fun rows(): List<AuditRow> =
        withAdminConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, byte_count FROM audit ORDER BY id").use { result ->
                    buildList {
                        while (result.next()) {
                            add(AuditRow(result.getString(1), result.getLong(2)))
                        }
                    }
                }
            }
        }

    fun removeRow(id: String) {
        withApplicationConnection { connection ->
            connection.prepareStatement("DELETE FROM audit WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
    }

    fun rejectInserts() {
        withAdminConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "ALTER TABLE audit ADD CONSTRAINT $rejectConstraintName CHECK (FALSE)",
                )
            }
        }
    }

    fun allowInserts() {
        withAdminConnection { connection ->
            connection.createStatement().use { statement ->
                runCatching {
                    statement.execute("ALTER TABLE audit DROP CONSTRAINT IF EXISTS $rejectConstraintName")
                }
            }
        }
    }

    fun applicationConnectionCount(): Int =
        withAdminConnection { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT COUNT(*)
                        FROM INFORMATION_SCHEMA.SESSIONS
                        WHERE USER_NAME = '${user.uppercase()}'
                        """.trimIndent(),
                    ).use { result ->
                        result.next()
                        result.getInt(1)
                    }
            }
        }

    override fun close() {
        try {
            tcpServer.stop()
        } finally {
            closeFailureForTests?.let { throw it }
        }
    }

    private fun <T> withAdminConnection(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(adminJdbcUrl, adminUser, adminPassword).use(block)

    private fun <T> withApplicationConnection(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(jdbcUrl, user, password).use(block)

    companion object {
        private const val DATABASE_NAME = "composition-audit"
        private const val ADMIN_USER = "composition_admin"
        private const val ADMIN_PASSWORD = "composition-admin-secret"
        private const val APPLICATION_USER = "composition_app"
        private const val APPLICATION_PASSWORD = "composition-app-secret"

        fun start(baseDirectory: Path): H2AuditDatabase {
            Files.createDirectories(baseDirectory)
            var tcpServer: Server? = null
            try {
                tcpServer =
                    Server
                        .createTcpServer(
                            "-tcpPort",
                            "0",
                            "-tcpAllowOthers",
                            "-ifNotExists",
                            "-baseDir",
                            baseDirectory.toAbsolutePath().toString(),
                        ).start()
                val port = tcpServer.port
                val adminJdbcUrl = "jdbc:h2:tcp://127.0.0.1:$port/$DATABASE_NAME"
                DriverManager.getConnection(adminJdbcUrl, "sa", "").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "CREATE USER IF NOT EXISTS $ADMIN_USER PASSWORD '$ADMIN_PASSWORD' ADMIN",
                        )
                        statement.execute(
                            "CREATE USER IF NOT EXISTS $APPLICATION_USER PASSWORD '$APPLICATION_PASSWORD'",
                        )
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS audit (
                                id VARCHAR(255) PRIMARY KEY,
                                byte_count BIGINT NOT NULL
                            )
                            """.trimIndent(),
                        )
                        statement.execute("GRANT SELECT, INSERT, DELETE ON audit TO $APPLICATION_USER")
                    }
                }
                return H2AuditDatabase(
                    tcpServer = tcpServer,
                    adminJdbcUrl = adminJdbcUrl,
                    adminUser = ADMIN_USER,
                    adminPassword = ADMIN_PASSWORD,
                    jdbcUrl = adminJdbcUrl,
                    user = APPLICATION_USER,
                    password = APPLICATION_PASSWORD,
                )
            } catch (failure: Throwable) {
                tcpServer?.let { server ->
                    runCatching { server.stop() }.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }
    }
}

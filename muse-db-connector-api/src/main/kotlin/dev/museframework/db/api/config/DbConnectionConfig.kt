package dev.museframework.db.api.config

import java.sql.Connection
import javax.sql.DataSource

sealed interface DbConnectionConfig {
    val transactionIsolation: DbTransactionIsolation
}

enum class DbTransactionIsolation(
    val jdbcLevel: Int?,
) {
    DEFAULT(null),
    NONE(Connection.TRANSACTION_NONE),
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE),
}

class GenericDbConnectionConfig(
    val url: String,
    val driverClassName: String,
    val user: String? = null,
    val password: String? = null,
    connectionProperties: Map<String, String> = emptyMap(),
    override val transactionIsolation: DbTransactionIsolation = DbTransactionIsolation.DEFAULT,
    val driverClassLoader: ClassLoader? = null,
) : DbConnectionConfig {
    val connectionProperties: Map<String, String> = connectionProperties.toMap()

    init {
        require(url.isNotBlank()) { "url must not be blank" }
        require(driverClassName.isNotBlank()) { "driverClassName must not be blank" }
    }

    override fun toString(): String =
        "GenericDbConnectionConfig(url=<redacted>, driverClassName=$driverClassName, user=$user, " +
            "password=<redacted>, connectionPropertyNames=${connectionProperties.keys}, " +
            "transactionIsolation=$transactionIsolation, driverClassLoader=$driverClassLoader)"
}

class DataSourceDbConnectionConfig(
    val dataSource: DataSource,
    val user: String? = null,
    val password: String? = null,
    override val transactionIsolation: DbTransactionIsolation = DbTransactionIsolation.DEFAULT,
) : DbConnectionConfig {
    override fun toString(): String =
        "DataSourceDbConnectionConfig(dataSource=${dataSource::class.java.name}, user=$user, " +
            "password=<redacted>, transactionIsolation=$transactionIsolation)"
}

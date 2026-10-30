package dev.museframework.db.api.dsl

import dev.museframework.db.api.config.DataSourceDbConnectionConfig
import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbConnectionConfig
import dev.museframework.db.api.config.DbTransactionIsolation
import dev.museframework.db.api.config.GenericDbConnectionConfig
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import java.util.UUID
import javax.sql.DataSource

@MuseDsl
class DbConfigBuilder internal constructor(
    private val name: String,
) {
    var docName: String = name
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var validateConnectivity: Boolean = true

    private var connection: DbConnectionConfig? = null

    fun generic(block: GenericDbConnectionBuilder.() -> Unit) {
        check(connection == null) { "A database connection is already configured" }
        connection = GenericDbConnectionBuilder().apply(block).build()
    }

    fun dataSource(
        dataSource: DataSource,
        user: String? = null,
        password: String? = null,
        transactionIsolation: DbTransactionIsolation = DbTransactionIsolation.DEFAULT,
    ) {
        check(connection == null) { "A database connection is already configured" }
        connection =
            DataSourceDbConnectionConfig(
                dataSource = dataSource,
                user = user,
                password = password,
                transactionIsolation = transactionIsolation,
            )
    }

    fun build(): DbConfig {
        val callSite = CallSiteCapture.capture()
        return DbConfig(
            name = name,
            connection = requireNotNull(connection) { "A generic or DataSource connection is required" },
            validateConnectivity = validateConnectivity,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
        )
    }
}

@MuseDsl
class GenericDbConnectionBuilder internal constructor() {
    var url: String = ""
    var driverClassName: String = ""
    var user: String? = null
    var password: String? = null
    var transactionIsolation: DbTransactionIsolation = DbTransactionIsolation.DEFAULT
    var driverClassLoader: ClassLoader? = null

    private val connectionProperties = linkedMapOf<String, String>()

    fun connectionProperty(name: String, value: String) {
        require(name.isNotBlank()) { "Connection property name must not be blank" }
        connectionProperties[name] = value
    }

    fun connectionProperties(values: Map<String, String>) {
        require(values.keys.none(String::isBlank)) { "Connection property names must not be blank" }
        connectionProperties.putAll(values)
    }

    fun build(): GenericDbConnectionConfig =
        GenericDbConnectionConfig(
            url = url,
            driverClassName = driverClassName,
            user = user,
            password = password,
            connectionProperties = connectionProperties,
            transactionIsolation = transactionIsolation,
            driverClassLoader = driverClassLoader,
        )
}

fun dbConfig(
    name: String,
    block: DbConfigBuilder.() -> Unit,
): DbConfig = DbConfigBuilder(name).apply(block).build()

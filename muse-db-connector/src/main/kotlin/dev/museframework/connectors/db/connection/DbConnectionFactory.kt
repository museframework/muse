package dev.museframework.connectors.db.connection

import dev.museframework.connectors.db.DbDriverLoadingException
import dev.museframework.db.api.config.DataSourceDbConnectionConfig
import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbConnectionConfig
import dev.museframework.db.api.config.GenericDbConnectionConfig
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Collections
import java.util.Properties

internal class DbConnectionFactory(
    private val config: DbConfig,
    properties: Properties,
) {
    private val propertyResolver = DbPropertyResolver(properties)

    fun open(): Connection {
        val connection =
            when (val connectionConfig = config.connection) {
                is GenericDbConnectionConfig -> openGeneric(connectionConfig)
                is DataSourceDbConnectionConfig -> openDataSource(connectionConfig)
            }

        return try {
            config.connection.transactionIsolation.jdbcLevel?.let {
                connection.transactionIsolation = it
            }
            connection
        } catch (failure: Throwable) {
            runCatching(connection::close)
            throw failure
        }
    }

    fun validate() {
        open().use { connection ->
            check(!connection.isClosed) { "Database configuration '${config.name}' returned a closed connection" }
        }
    }

    private fun openGeneric(connectionConfig: GenericDbConnectionConfig): Connection {
        val classLoader =
            connectionConfig.driverClassLoader
                ?: Thread.currentThread().contextClassLoader
                ?: DbConnectionFactory::class.java.classLoader
        val url = propertyResolver.resolve(connectionConfig.url)
        val connectionProperties =
            Properties().apply {
                connectionConfig.connectionProperties.forEach { (name, value) ->
                    setProperty(name, propertyResolver.resolve(value))
                }
                connectionConfig.user?.let { setProperty("user", propertyResolver.resolve(it)) }
                connectionConfig.password?.let { setProperty("password", propertyResolver.resolve(it)) }
            }

        // Warm DriverManager under the caller's TCCL before any child-realm work, then load the
        // driver without installing that realm as TCCL. Only Driver.connect uses the child TCCL.
        Collections.list(DriverManager.getDrivers())
        val driver = instantiateDriver(connectionConfig.driverClassName, classLoader)
        return withContextClassLoader(classLoader) {
            driver.connect(url, connectionProperties)
                ?: throw SQLException(
                    "JDBC driver '${connectionConfig.driverClassName}' does not accept the configured URL",
                )
        }
    }

    private fun instantiateDriver(
        driverClassName: String,
        classLoader: ClassLoader,
    ): Driver =
        try {
            val location =
                DbConnectionFactory::class.java.protectionDomain?.codeSource?.location
                    ?: throw DbDriverLoadingException("Cannot load JDBC driver '$driverClassName'")
            JdbcIsolationClassLoader(arrayOf(location), classLoader).use { isolationLoader ->
                val isolationClass = isolationLoader.loadClass(JdbcDriverIsolation::class.java.name)
                val loadUnregistered =
                    isolationClass.getMethod(
                        "loadUnregistered",
                        String::class.java,
                        ClassLoader::class.java,
                    )
                try {
                    loadUnregistered.invoke(null, driverClassName, classLoader) as Driver
                } catch (failure: InvocationTargetException) {
                    throw unwrapDriverLoad(driverClassName, failure)
                }
            }
        } catch (failure: DbDriverLoadingException) {
            throw failure
        } catch (failure: InvocationTargetException) {
            throw DbDriverLoadingException(
                "JDBC driver '$driverClassName' failed during construction",
                failure.targetException,
            )
        } catch (failure: ReflectiveOperationException) {
            throw DbDriverLoadingException("Cannot load JDBC driver '$driverClassName'", failure)
        } catch (failure: LinkageError) {
            throw DbDriverLoadingException("Cannot link JDBC driver '$driverClassName'", failure)
        }

    private fun unwrapDriverLoad(
        driverClassName: String,
        failure: InvocationTargetException,
    ): Throwable {
        val target = failure.targetException ?: return failure
        if (target is IllegalArgumentException) {
            throw DbDriverLoadingException(
                "Configured class '$driverClassName' does not implement java.sql.Driver",
                target,
            )
        }
        return target
    }

    private fun openDataSource(connectionConfig: DataSourceDbConnectionConfig): Connection {
        val user = connectionConfig.user?.let(propertyResolver::resolve)
        val password = connectionConfig.password?.let(propertyResolver::resolve)
        return if (user != null) {
            connectionConfig.dataSource.getConnection(user, password.orEmpty())
        } else {
            connectionConfig.dataSource.connection
        }
    }

    private inline fun <T> withContextClassLoader(
        classLoader: ClassLoader,
        action: () -> T,
    ): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        if (previous === classLoader) return action()
        thread.contextClassLoader = classLoader
        return try {
            action()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private class JdbcIsolationClassLoader(
        urls: Array<URL>,
        parent: ClassLoader,
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (name != JdbcDriverIsolation::class.java.name) {
                return super.loadClass(name, resolve)
            }
            synchronized(getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name)
                if (loaded == null) {
                    loaded = findClass(name)
                }
                if (resolve) {
                    resolveClass(loaded)
                }
                return loaded
            }
        }
    }
}

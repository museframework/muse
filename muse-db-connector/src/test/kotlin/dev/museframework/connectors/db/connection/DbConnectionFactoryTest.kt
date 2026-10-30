package dev.museframework.connectors.db.connection

import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.GenericDbConnectionConfig
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DbConnectionFactoryTest {
    @Test
    fun `generic connection uses explicit loader without global driver registration`() {
        val originalLoader = Thread.currentThread().contextClassLoader
        val loader = TrackingClassLoader(javaClass.classLoader, FakeDriver::class.java.name)
        val properties = Properties().apply {
            setProperty("db.host", "database.example.test")
            setProperty("DB_USER", "muse")
            setProperty("db.password", "secret")
        }
        FakeDriver.reset()
        val config =
            DbConfig(
                name = "isolated",
                connection =
                    GenericDbConnectionConfig(
                        url = "jdbc:fake://${'$'}{db.host}/orders",
                        driverClassName = FakeDriver::class.java.name,
                        user = "${'$'}{db-user}",
                        password = "${'$'}{db.password}",
                        driverClassLoader = loader,
                    ),
            )

        val connection = DbConnectionFactory(config, properties).open()

        assertTrue(loader.requested)
        assertSame(loader, FakeDriver.contextClassLoader)
        assertSame(originalLoader, Thread.currentThread().contextClassLoader)
        assertEquals("jdbc:fake://database.example.test/orders", FakeDriver.url)
        assertEquals("muse", FakeDriver.properties?.getProperty("user"))
        assertEquals("secret", FakeDriver.properties?.getProperty("password"))
        assertFalse(connection.isClosed)
        connection.close()
        assertTrue(connection.isClosed)
    }

    @Test
    fun `auto-registered isolated driver is dropped and the pre-registered driver remains`() {
        val driverName =
            "dev.museframework.connectors.db.connection.DbConnectionFactoryTest\$AutoRegisteringDriver"
        val probeName =
            "dev.museframework.connectors.db.connection.DbConnectionFactoryTest\$IsolatedDriverProbe"
        val loader =
            SingleBinaryDefiningLoader(
                parent = javaClass.classLoader,
                binaryName = driverName,
                bytes = classFileBytes(driverName),
            )
        val probeClass = loader.defineProbe(probeName, classFileBytes(probeName))
        val preRegistered = FakeDriver()
        DriverManager.registerDriver(preRegistered)
        try {
            val connection =
                DbConnectionFactory(
                    DbConfig(
                        name = "autoreg",
                        connection =
                            GenericDbConnectionConfig(
                                url = "jdbc:autoreg:orders",
                                driverClassName = driverName,
                                driverClassLoader = loader,
                            ),
                    ),
                    Properties(),
                ).open()
            connection.close()

            assertTrue(driversVisibleToTest().any { it === preRegistered })
            val seenLoaders =
                probeClass.getDeclaredMethod("driverClassLoaders").invoke(
                    probeClass.getDeclaredConstructor().newInstance(),
                ) as Array<*>
            assertFalse(
                seenLoaders.any { it === loader },
                "isolated driver remained registered",
            )
        } finally {
            if (driversVisibleToTest().any { it === preRegistered }) {
                DriverManager.deregisterDriver(preRegistered)
            }
        }
    }

    @Test
    fun `real H2 driver from disposable loader is not retained in DriverManager`() {
        val h2Jar =
            org.h2.Driver::class.java.protectionDomain?.codeSource?.location
                ?: error("H2 driver jar location unavailable")
        val loader =
            ChildFirstUrlClassLoader(
                urls = arrayOf(h2Jar),
                parent = javaClass.classLoader,
                childFirstPrefixes = listOf("org.h2."),
            )
        try {
            val connection =
                DbConnectionFactory(
                    DbConfig(
                        name = "h2-isolated",
                        connection =
                            GenericDbConnectionConfig(
                                url = "jdbc:h2:mem:isolated-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                                driverClassName = "org.h2.Driver",
                                user = "sa",
                                password = "",
                                driverClassLoader = loader,
                            ),
                    ),
                    Properties(),
                ).open()
            connection.close()

            assertFalse(
                driversVisibleToTest().any { it.javaClass.classLoader === loader },
                "disposable H2 driver realm remained registered",
            )
        } finally {
            loader.close()
        }
    }

    class AutoRegisteringDriver : Driver {
        override fun connect(
            url: String,
            info: Properties?,
        ): Connection? {
            if (!acceptsURL(url)) return null
            val closed = AtomicBoolean(false)
            return Proxy.newProxyInstance(
                AutoRegisteringDriver::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "close" -> {
                        closed.set(true)
                        null
                    }
                    "isClosed" -> closed.get()
                    "isWrapperFor" -> false
                    "unwrap" -> throw SQLException("Not a wrapper")
                    "toString" -> "AutoRegisteringConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> primitiveDefault(method.returnType)
                }
            } as Connection
        }

        override fun acceptsURL(url: String?): Boolean = url?.startsWith("jdbc:autoreg:") == true

        override fun getPropertyInfo(
            url: String?,
            info: Properties?,
        ): Array<DriverPropertyInfo> = emptyArray()

        override fun getMajorVersion(): Int = 1

        override fun getMinorVersion(): Int = 0

        override fun jdbcCompliant(): Boolean = false

        override fun getParentLogger(): Logger = Logger.getGlobal()

        private fun primitiveDefault(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }

        companion object {
            init {
                DriverManager.registerDriver(AutoRegisteringDriver())
            }
        }
    }

    class IsolatedDriverProbe {
        fun driverClassLoaders(): Array<ClassLoader?> {
            val found = mutableListOf<ClassLoader?>()
            val drivers = DriverManager.getDrivers()
            while (drivers.hasMoreElements()) {
                found.add(drivers.nextElement().javaClass.classLoader)
            }
            return found.toTypedArray()
        }
    }

    class FakeDriver : Driver {
        override fun connect(url: String, info: Properties?): Connection {
            Companion.url = url
            properties = info
            contextClassLoader = Thread.currentThread().contextClassLoader
            val closed = AtomicBoolean(false)
            return Proxy.newProxyInstance(
                FakeDriver::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "close" -> {
                        closed.set(true)
                        null
                    }
                    "isClosed" -> closed.get()
                    "isWrapperFor" -> false
                    "unwrap" -> throw java.sql.SQLException("Not a wrapper")
                    "toString" -> "FakeConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> primitiveDefault(method.returnType)
                }
            } as Connection
        }

        override fun acceptsURL(url: String?): Boolean = url?.startsWith("jdbc:fake:") == true

        override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> = emptyArray()

        override fun getMajorVersion(): Int = 1

        override fun getMinorVersion(): Int = 0

        override fun jdbcCompliant(): Boolean = false

        override fun getParentLogger(): Logger = Logger.getGlobal()

        companion object {
            var url: String? = null
            var properties: Properties? = null
            var contextClassLoader: ClassLoader? = null

            fun reset() {
                url = null
                properties = null
                contextClassLoader = null
            }

            private fun primitiveDefault(type: Class<*>): Any? =
                when (type) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Byte.TYPE -> 0.toByte()
                    java.lang.Short.TYPE -> 0.toShort()
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Character.TYPE -> '\u0000'
                    else -> null
                }
        }
    }

    private fun classFileBytes(binaryName: String): ByteArray {
        val resourceName = binaryName.replace('.', '/') + ".class"
        val stream =
            javaClass.classLoader.getResourceAsStream(resourceName)
                ?: error("Missing test class resource $resourceName")
        return stream.use { it.readBytes() }
    }

    private fun driversVisibleToTest(): List<Driver> {
        val drivers = mutableListOf<Driver>()
        val registered = DriverManager.getDrivers()
        while (registered.hasMoreElements()) {
            drivers.add(registered.nextElement())
        }
        return drivers
    }

    private class SingleBinaryDefiningLoader(
        parent: ClassLoader,
        private val binaryName: String,
        private val bytes: ByteArray,
    ) : ClassLoader(parent) {
        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (name != binaryName) {
                return super.loadClass(name, resolve)
            }
            synchronized(getClassLoadingLock(name)) {
                var defined = findLoadedClass(name)
                if (defined == null) {
                    defined = defineClass(binaryName, bytes, 0, bytes.size)
                }
                if (resolve) {
                    resolveClass(defined)
                }
                return defined
            }
        }

        fun defineProbe(
            probeBinaryName: String,
            probeBytes: ByteArray,
        ): Class<*> = defineClass(probeBinaryName, probeBytes, 0, probeBytes.size)
    }

    private class TrackingClassLoader(
        parent: ClassLoader,
        private val trackedClass: String,
    ) : ClassLoader(parent) {
        var requested: Boolean = false

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == trackedClass) requested = true
            return super.loadClass(name, resolve)
        }
    }

    private class ChildFirstUrlClassLoader(
        urls: Array<java.net.URL>,
        parent: ClassLoader,
        private val childFirstPrefixes: List<String>,
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (childFirstPrefixes.any(name::startsWith)) {
                synchronized(getClassLoadingLock(name)) {
                    findLoadedClass(name)?.let { loaded ->
                        if (resolve) resolveClass(loaded)
                        return loaded
                    }
                    val defined = findClass(name)
                    if (resolve) resolveClass(defined)
                    return defined
                }
            }
            return super.loadClass(name, resolve)
        }
    }
}

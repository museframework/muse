package dev.museframework.connectors.db.processor

import dev.museframework.connectors.db.connection.DbConnectionFactory
import dev.museframework.db.api.config.DbConfig
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.config.GenericDbConnectionConfig
import dev.museframework.db.api.operation.DbSelectConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbOperationCancellationTest {
    @BeforeEach
    fun resetDriverState() {
        BlockingSelectDriver.reset()
    }

    @Test
    fun `cancellation during blocking select closes JDBC resources exactly once`() =
        runBlocking {
            val registered = BlockingSelectDriver()
            DriverManager.registerDriver(registered)
            try {
                val dbConfig =
                    DbConfig(
                        name = "blocking-select",
                        connection =
                            GenericDbConnectionConfig(
                                url = "jdbc:blocking-select:orders",
                                driverClassName = BlockingSelectDriver::class.java.name,
                            ),
                    )
                val evaluator =
                    object : MuseExpressionEvaluator {
                        override fun evaluate(
                            expression: String,
                            event: MuseEvent,
                        ): TypedValue<Any>? = TypedValue.of(expression as Any)
                    }
                val processor =
                    DbOperationMuseProcessor(
                        component =
                            DbSelectConfig(
                                configRef = DbConfigRef.ByReference(dbConfig),
                                sql = "SELECT 1",
                            ),
                        connectionFactory = DbConnectionFactory(dbConfig, Properties()),
                        expressionEvaluator = evaluator,
                    )
                val context =
                    ExecutionContext(
                        event = MuseEvent(),
                        errorHandler = null,
                        coroutineScope = this,
                        expressionEvaluator = evaluator,
                    )

                val job =
                    async(Dispatchers.Default) {
                        try {
                            processor.process(context)
                        } catch (_: CancellationException) {
                            // expected when cancelled around the IO handoff
                        }
                    }

                assertTrue(BlockingSelectDriver.queryEntered.await(5, TimeUnit.SECONDS))
                job.cancel()
                BlockingSelectDriver.allowQueryReturn.countDown()
                job.cancelAndJoin()
                context.streamingResources.releaseAll()

                assertEquals(1, BlockingSelectDriver.connectionCloses.get())
                assertEquals(1, BlockingSelectDriver.statementCloses.get())
                assertEquals(1, BlockingSelectDriver.resultSetCloses.get())
            } finally {
                DriverManager.deregisterDriver(registered)
            }
        }

    class BlockingSelectDriver : Driver {
        override fun connect(
            url: String,
            info: Properties?,
        ): Connection? {
            if (!acceptsURL(url)) return null
            val connectionClosed = AtomicBoolean(false)
            return Proxy.newProxyInstance(
                BlockingSelectDriver::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "prepareStatement" -> preparedStatement()
                    "close" -> {
                        if (connectionClosed.compareAndSet(false, true)) {
                            connectionCloses.incrementAndGet()
                        }
                        null
                    }
                    "isClosed" -> connectionClosed.get()
                    "isWrapperFor" -> false
                    "unwrap" -> throw SQLException("Not a wrapper")
                    "toString" -> "BlockingSelectConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> primitiveDefault(method.returnType)
                }
            } as Connection
        }

        override fun acceptsURL(url: String?): Boolean = url?.startsWith("jdbc:blocking-select:") == true

        override fun getPropertyInfo(
            url: String?,
            info: Properties?,
        ): Array<DriverPropertyInfo> = emptyArray()

        override fun getMajorVersion(): Int = 1

        override fun getMinorVersion(): Int = 0

        override fun jdbcCompliant(): Boolean = false

        override fun getParentLogger(): Logger = Logger.getGlobal()

        companion object {
            @Volatile
            var queryEntered = CountDownLatch(1)

            @Volatile
            var allowQueryReturn = CountDownLatch(1)
            val connectionCloses = AtomicInteger(0)
            val statementCloses = AtomicInteger(0)
            val resultSetCloses = AtomicInteger(0)

            fun reset() {
                queryEntered = CountDownLatch(1)
                allowQueryReturn = CountDownLatch(1)
                connectionCloses.set(0)
                statementCloses.set(0)
                resultSetCloses.set(0)
            }

            private fun preparedStatement(): PreparedStatement {
                val statementClosed = AtomicBoolean(false)
                return Proxy.newProxyInstance(
                    BlockingSelectDriver::class.java.classLoader,
                    arrayOf(PreparedStatement::class.java),
                ) { proxy, method, args ->
                    when (method.name) {
                        "executeQuery" -> {
                            queryEntered.countDown()
                            assertTrue(allowQueryReturn.await(5, TimeUnit.SECONDS))
                            resultSet()
                        }
                        "setQueryTimeout", "setFetchSize", "setMaxRows", "setObject", "setNull", "clearParameters" -> null
                        "close" -> {
                            if (statementClosed.compareAndSet(false, true)) {
                                statementCloses.incrementAndGet()
                            }
                            null
                        }
                        "isClosed" -> statementClosed.get()
                        "isWrapperFor" -> false
                        "unwrap" -> throw SQLException("Not a wrapper")
                        "toString" -> "BlockingSelectStatement"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        else -> primitiveDefault(method.returnType)
                    }
                } as PreparedStatement
            }

            private fun resultSet(): ResultSet {
                val resultClosed = AtomicBoolean(false)
                return Proxy.newProxyInstance(
                    BlockingSelectDriver::class.java.classLoader,
                    arrayOf(ResultSet::class.java),
                ) { proxy, method, args ->
                    when (method.name) {
                        "next" -> false
                        "getMetaData" ->
                            Proxy.newProxyInstance(
                                BlockingSelectDriver::class.java.classLoader,
                                arrayOf(ResultSetMetaData::class.java),
                            ) { _, metaMethod, _ ->
                                when (metaMethod.name) {
                                    "getColumnCount" -> 1
                                    "getColumnLabel", "getColumnName" -> "n"
                                    else -> primitiveDefault(metaMethod.returnType)
                                }
                            }
                        "getObject" -> 1
                        "wasNull" -> false
                        "close" -> {
                            if (resultClosed.compareAndSet(false, true)) {
                                resultSetCloses.incrementAndGet()
                            }
                            null
                        }
                        "isClosed" -> resultClosed.get()
                        "isWrapperFor" -> false
                        "unwrap" -> throw SQLException("Not a wrapper")
                        "toString" -> "BlockingSelectResultSet"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        else -> primitiveDefault(method.returnType)
                    }
                } as ResultSet
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
}

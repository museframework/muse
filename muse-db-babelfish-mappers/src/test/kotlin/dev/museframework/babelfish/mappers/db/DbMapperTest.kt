package dev.museframework.babelfish.mappers.db

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.config.DbTransactionIsolation
import dev.museframework.db.api.config.GenericDbConnectionConfig
import dev.museframework.db.api.model.DbGeneratedKeys
import dev.museframework.db.api.model.DbParameterSource
import dev.museframework.db.api.model.DbParameterType
import dev.museframework.db.api.model.DbQuerySettings
import dev.museframework.db.api.operation.DbExecuteScriptConfig
import dev.museframework.db.api.source.DbRowListenerConfig
import dev.museframework.engine.api.streaming.StreamingStrategy
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.JDBCType
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DbMapperTest {
    private val context = MappingContext(BabelfishRegistry(), Path.of("database.xml"))

    @Test
    fun `generic configuration mapper preserves JDBC connection contract`() {
        val connection =
            RawElement(
                namespace = "db",
                tag = "generic-connection",
                attrs =
                    mapOf(
                        "url" to "jdbc:postgresql://localhost/orders",
                        "driverClassName" to "org.postgresql.Driver",
                        "user" to "muse",
                        "password" to "secret",
                        "transactionIsolation" to "READ_COMMITTED",
                    ),
                children = emptyList(),
                location = SourceLocation(2, 5),
            )
        val raw =
            RawConfig(
                id = "database",
                namespace = "db",
                tag = "config",
                attrs = mapOf("name" to "database", "doc:name" to "Database"),
                children = listOf(connection),
                location = SourceLocation(1, 1),
            )

        val mapped = DbConfigMapper.map(raw, context)
        val generic = assertIs<GenericDbConnectionConfig>(mapped.connection)
        assertEquals("database", mapped.name)
        assertEquals("Database", mapped.docName)
        assertEquals(DbTransactionIsolation.READ_COMMITTED, generic.transactionIsolation)
        assertEquals("secret", generic.password)

        val code = DbConfigCodeEmitter.emit(mapped, CodegenContext("generated"))
        assertContains(code, "dbConfig(\"database\")")
        assertContains(code, "driverClassName = \"org.postgresql.Driver\"")
        assertContains(code, "DbTransactionIsolation.READ_COMMITTED")
    }

    @Test
    fun `select mapper and emitter preserve SQL parameters settings and target`() {
        val element =
            RawElement(
                namespace = "db",
                tag = "select",
                attrs =
                    mapOf(
                        "config-ref" to "database",
                        "fetchSize" to "25",
                        "maxRows" to "100",
                        "target" to "rows",
                    ),
                children =
                    listOf(
                        RawElement(
                            namespace = "db",
                            tag = "sql",
                            attrs = emptyMap(),
                            children = emptyList(),
                            location = SourceLocation(4, 9),
                            textContent = "SELECT * FROM account WHERE id = :id",
                        ),
                        RawElement(
                            namespace = "db",
                            tag = "input-parameters",
                            attrs = emptyMap(),
                            children = emptyList(),
                            location = SourceLocation(5, 9),
                            textContent = "#[{ id: payload }]",
                        ),
                    ),
                location = SourceLocation(3, 5),
            )

        val mapped = DbSelectMapper.map(element, context)
        assertEquals(DbConfigRef.ByName("database"), mapped.configRef)
        assertEquals(DbParameterSource.Expression("#[{ id: payload }]"), mapped.inputParameters)
        assertEquals(25, mapped.settings.fetchSize)
        assertEquals("rows", mapped.targetConfig?.target)

        val codeContext = CodegenContext("generated")
        val code = DbSelectCodeEmitter.emit(mapped, codeContext)
        assertContains(code, "dbSelect(DbConfigRef.ByName(\"database\"))")
        assertContains(code, "inputParameters(\"#[{ id: payload }]\")")
        assertContains(code, "fetchSize = 25")
        assertContains(code, "target = \"rows\"")
    }

    @Test
    fun `execute script mapper rejects fetchSize`() {
        val element =
            RawElement(
                namespace = "db",
                tag = "execute-script",
                attrs =
                    mapOf(
                        "config-ref" to "database",
                        "fetchSize" to "10",
                    ),
                children =
                    listOf(
                        RawElement(
                            namespace = "db",
                            tag = "sql",
                            attrs = emptyMap(),
                            children = emptyList(),
                            location = SourceLocation(4, 9),
                            textContent = "INSERT INTO audit(id) VALUES (1)",
                        ),
                    ),
                location = SourceLocation(3, 5),
            )

        val exception =
            assertFailsWith<IllegalArgumentException> {
                DbExecuteScriptMapper.map(element, context)
            }

        assertContains(exception.message.orEmpty(), "fetchSize")
        assertContains(exception.message.orEmpty(), "execute-script")
    }

    @Test
    fun `row listener mapper rejects useColumnNumbers`() {
        val element =
            RawElement(
                namespace = "db",
                tag = "listener",
                attrs =
                    mapOf(
                        "config-ref" to "database",
                        "table" to "outbox",
                        "useColumnNumbers" to "true",
                    ),
                children = emptyList(),
                location = SourceLocation(3, 5),
            )

        val exception =
            assertFailsWith<IllegalArgumentException> {
                DbRowListenerMapper.map(element, context)
            }

        assertContains(exception.message.orEmpty(), "useColumnNumbers")
        assertContains(exception.message.orEmpty(), "listener")
    }

    @Test
    fun `row listener emitter writes fetch size and max rows without column numbers`() {
        val config =
            DbRowListenerConfig(
                configRef = DbConfigRef.ByName("database"),
                table = "outbox",
                settings =
                    DbQuerySettings(
                        fetchSize = 25,
                        maxRows = 10,
                        useColumnNumbers = true,
                    ),
            )

        val code = DbRowListenerCodeEmitter.emit(config, CodegenContext("generated"))

        assertContains(code, "fetchSize = 25")
        assertContains(code, "maxRows = 10")
        assertFalse(code.contains("useColumnNumbers"), code)
    }

    @Test
    fun `execute script emitter writes query timeout only`() {
        val config =
            DbExecuteScriptConfig(
                configRef = DbConfigRef.ByName("database"),
                sql = "INSERT INTO audit(id) VALUES (1)",
                settings =
                    DbQuerySettings(
                        queryTimeout = Duration.ofSeconds(5),
                        fetchSize = 10,
                        maxRows = 20,
                        useColumnNumbers = true,
                    ),
            )

        val code = DbExecuteScriptCodeEmitter.emit(config, CodegenContext("generated"))

        assertContains(code, "queryTimeout")
        assertFalse(code.contains("fetchSize"), code)
        assertFalse(code.contains("maxRows"), code)
        assertFalse(code.contains("useColumnNumbers"), code)
    }

    @Test
    fun `stored procedure mapper maps in-out-parameter children to values and parameter types`() {
        val element =
            raw(
                "stored-procedure",
                attrs = mapOf("config-ref" to "database"),
                children =
                    listOf(
                        raw("sql", text = "{ call add_values(:result, :label) }"),
                        raw(
                            "in-out-parameters",
                            children =
                                listOf(
                                    raw("in-out-parameter", attrs = mapOf("key" to "result", "value" to "3", "type" to "INTEGER")),
                                    raw("in-out-parameter", attrs = mapOf("key" to "label", "value" to "abc")),
                                ),
                        ),
                    ),
            )

        val mapped = DbStoredProcedureMapper.map(element, context)

        assertEquals(
            DbParameterSource.Values(linkedMapOf("result" to "3", "label" to "abc")),
            mapped.inOutParameters,
        )
        assertEquals(DbParameterType(JDBCType.INTEGER), mapped.parameterTypes["result"])
        assertFalse(mapped.parameterTypes.containsKey("label"))

        val code = DbStoredProcedureCodeEmitter.emit(mapped, CodegenContext("generated"))
        assertContains(code, "inOutParameters(mapOf(\"result\" to \"3\", \"label\" to \"abc\"))")
    }

    @Test
    fun `stored procedure mapper keeps in-out-parameters text as expression`() {
        val element =
            raw(
                "stored-procedure",
                attrs = mapOf("config-ref" to "database"),
                children =
                    listOf(
                        raw("sql", text = "{ call add_values(:result) }"),
                        raw("in-out-parameters", text = "#[{ result: 3 }]"),
                    ),
            )

        val mapped = DbStoredProcedureMapper.map(element, context)

        assertEquals(DbParameterSource.Expression("#[{ result: 3 }]"), mapped.inOutParameters)
    }

    @Test
    fun `stored procedure mapper rejects in-out-parameter child without key`() {
        val element =
            raw(
                "stored-procedure",
                attrs = mapOf("config-ref" to "database"),
                children =
                    listOf(
                        raw("sql", text = "{ call p(:a) }"),
                        raw(
                            "in-out-parameters",
                            children = listOf(raw("in-out-parameter", attrs = mapOf("value" to "1"))),
                        ),
                    ),
            )

        val exception = assertFailsWith<IllegalArgumentException> { DbStoredProcedureMapper.map(element, context) }

        assertContains(exception.message.orEmpty(), "in-out-parameter")
    }

    @Test
    fun `select mapper defaults to repeatable in-memory streaming`() {
        val mapped = DbSelectMapper.map(selectWith(emptyList()), context)

        assertEquals(StreamingStrategy.RepeatableInMemory(), mapped.streamingStrategy)
    }

    @Test
    fun `select mapper maps non-repeatable streaming strategy`() {
        val strategy = raw("streaming-strategy", children = listOf(raw("non-repeatable-iterable")))

        val mapped = DbSelectMapper.map(selectWith(listOf(strategy)), context)

        assertEquals(StreamingStrategy.NonRepeatable, mapped.streamingStrategy)
        val code = DbSelectCodeEmitter.emit(mapped, CodegenContext("generated"))
        assertContains(code, "streamingStrategy = StreamingStrategy.NonRepeatable")
    }

    @Test
    fun `select mapper maps non-repeatable streaming strategy declared directly under select`() {
        val mapped = DbSelectMapper.map(selectWith(listOf(raw("non-repeatable-iterable"))), context)

        assertEquals(StreamingStrategy.NonRepeatable, mapped.streamingStrategy)
    }

    @Test
    fun `select mapper maps repeatable in-memory streaming buffer sizes`() {
        val strategy =
            raw(
                "streaming-strategy",
                children =
                    listOf(
                        raw(
                            "repeatable-in-memory-iterable",
                            attrs =
                                mapOf(
                                    "initialBufferSize" to "10",
                                    "bufferSizeIncrement" to "5",
                                    "maxBufferSize" to "50",
                                ),
                        ),
                    ),
            )

        val mapped = DbSelectMapper.map(selectWith(listOf(strategy)), context)

        assertEquals(
            StreamingStrategy.RepeatableInMemory(
                initialObjectBufferSize = 10,
                objectBufferSizeIncrement = 5,
                maxObjectBufferSize = 50,
            ),
            mapped.streamingStrategy,
        )
    }

    @Test
    fun `select mapper rejects repeatable file-store streaming for object streams`() {
        val strategy = raw("streaming-strategy", children = listOf(raw("repeatable-file-store-iterable")))

        val exception = assertFailsWith<IllegalArgumentException> { DbSelectMapper.map(selectWith(listOf(strategy)), context) }

        assertContains(exception.message.orEmpty(), "repeatable-file-store-iterable")
    }

    @Test
    fun `generated keys are read from column-name children merged with attributes`() {
        val element =
            insertWith(
                attrs = mapOf("autoGeneratedKeysColumnNames" to "id"),
                children =
                    listOf(
                        raw(
                            "auto-generated-keys-column-names",
                            children =
                                listOf(
                                    raw("auto-generated-keys-column-name", text = "created_at"),
                                    raw("auto-generated-keys-column-name", text = " id "),
                                ),
                        ),
                    ),
            )

        val mapped = DbInsertMapper.map(element, context)

        assertEquals(DbGeneratedKeys(true, listOf("id", "created_at"), emptyList()), mapped.generatedKeys)
    }

    @Test
    fun `generated keys are read from column-index children`() {
        val element =
            insertWith(
                children =
                    listOf(
                        raw(
                            "auto-generated-keys-column-indexes",
                            children =
                                listOf(
                                    raw("auto-generated-keys-column-index", text = "1"),
                                    raw("auto-generated-keys-column-index", text = "3"),
                                ),
                        ),
                    ),
            )

        val mapped = DbInsertMapper.map(element, context)

        assertEquals(DbGeneratedKeys(true, emptyList(), listOf(1, 3)), mapped.generatedKeys)
    }

    @Test
    fun `generated keys accept singular column-name and column-index tags`() {
        val named = DbUpdateMapper.map(insertWith("update", children = listOf(raw("column-name", text = "id"))), context)
        val indexed = DbUpdateMapper.map(insertWith("update", children = listOf(raw("column-index", text = "2"))), context)

        assertEquals(DbGeneratedKeys(true, listOf("id"), emptyList()), named.generatedKeys)
        assertEquals(DbGeneratedKeys(true, emptyList(), listOf(2)), indexed.generatedKeys)
    }

    @Test
    fun `generated keys stay disabled without attributes or children`() {
        val mapped = DbInsertMapper.map(insertWith(), context)

        assertEquals(DbGeneratedKeys(), mapped.generatedKeys)
    }

    private fun raw(
        tag: String,
        attrs: Map<String, String> = emptyMap(),
        children: List<RawElement> = emptyList(),
        text: String? = null,
    ) = RawElement(
        namespace = "db",
        tag = tag,
        attrs = attrs,
        children = children,
        location = SourceLocation(3, 5),
        textContent = text,
    )

    private fun selectWith(children: List<RawElement>) =
        raw(
            "select",
            attrs = mapOf("config-ref" to "database"),
            children = listOf(raw("sql", text = "SELECT 1")) + children,
        )

    private fun insertWith(
        tag: String = "insert",
        attrs: Map<String, String> = emptyMap(),
        children: List<RawElement> = emptyList(),
    ) = raw(
        tag,
        attrs = mapOf("config-ref" to "database") + attrs,
        children = listOf(raw("sql", text = "INSERT INTO t(a) VALUES (1)")) + children,
    )
}

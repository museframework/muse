package dev.museframework.babelfish.mappers.db

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.db.api.config.DbConfigRef
import dev.museframework.db.api.config.DbTransactionIsolation
import dev.museframework.db.api.config.GenericDbConnectionConfig
import dev.museframework.db.api.model.DbBatchParameterSource
import dev.museframework.db.api.model.DbGeneratedKeys
import dev.museframework.db.api.model.DbParameterSource
import dev.museframework.db.api.model.DbParameterType
import dev.museframework.db.api.model.DbQuerySettings
import dev.museframework.db.api.operation.DbBulkInsertConfig
import dev.museframework.db.api.operation.DbExecuteScriptConfig
import dev.museframework.db.api.operation.DbStoredProcedureConfig
import dev.museframework.db.api.source.DbRowListenerConfig
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.streaming.StreamingStrategy
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.JDBCType
import java.time.Duration
import java.util.Locale
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

        val code = DbConfigCodeEmitter.emit(mapped, CodegenContext("generated")).toString()
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
        val code = DbSelectCodeEmitter.emit(mapped, codeContext).toString()
        assertContains(code, "DbConfigRef.ByName(\"database\")")
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

        val code = DbRowListenerCodeEmitter.emit(config, CodegenContext("generated")).toString()

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

        val code = DbExecuteScriptCodeEmitter.emit(config, CodegenContext("generated")).toString()

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
                                    raw("in-out-parameter", attrs = mapOf("key" to "label"), text = "\n    abc\n"),
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

        val code = DbStoredProcedureCodeEmitter.emit(mapped, CodegenContext("generated")).toString()
        assertContains(
            code,
            "inOutParameters(mapOf<String, Any?>(\"result\" to \"3\", \"label\" to \"abc\"))",
        )
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
                        raw("in-out-parameters", text = "\n    #[{ result: 3 }]\r\n"),
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
        val code = DbSelectCodeEmitter.emit(mapped, CodegenContext("generated")).toString()
        assertContains(code, "StreamingStrategy.NonRepeatable")
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

    @Test
    fun `inline SQL keeps boundary whitespace and parameter expressions discard XML indentation`() {
        val sql = "  SELECT '\"\$price' WHERE id = :id\r\n\r\n"
        val expression = "  #[{ id: payload }]\r\n"
        val operation = raw(
            "select",
            attrs = mapOf("config-ref" to "database"),
            children = listOf(raw("sql", text = sql), raw("input-parameters", text = expression)),
        )
        val select = DbSelectMapper.map(operation, context)
        assertEquals(sql, select.sql)
        assertEquals(DbParameterSource.Expression("#[{ id: payload }]"), select.inputParameters)
    }

    @Test
    fun `explicit batch rows preserve null and numeric types and distinguish an empty batch from the payload default`() {
        val row = mapOf("nullable" to null, "byte" to 1.toByte(), "short" to 2.toShort(), "long" to 3L, "float" to 4.5f)
        val config = DbBulkInsertConfig(DbConfigRef.ByName("db"), "INSERT INTO t VALUES (:id)")
        fun emit(rows: List<Map<String, Any?>>): String =
            DbBulkInsertCodeEmitter.emit(config.copy(batchParameters = DbBatchParameterSource.Values(rows)), CodegenContext("test"))
                .toString()

        assertContains(emit(emptyList()), "batchParameters(listOf())")
        val body = emit(listOf(emptyMap(), row))
        assertContains(body, "mapOf<String, Any?>(),")
        assertContains(body, "\"nullable\" to null")
        assertContains(body, "\"byte\" to (1).toByte()")
        assertContains(body, "\"short\" to (2).toShort()")
        assertContains(body, "\"long\" to 3L")
        assertContains(body, "\"float\" to 4.5F")
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    fun `database emitter output compiles and materializes under arabic format locale`() {
        val originalLocale = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"))
            val config =
                DbBulkInsertConfig(
                    configRef = DbConfigRef.ByName("database"),
                    sql = "INSERT INTO numbers VALUES (:int, :long)",
                    batchParameters =
                        DbBatchParameterSource.Values(listOf(numericValuesWithNonFinite(8081, 8082L))),
                )
            val source = generatedOperationSource(config)

            withCompiledKotlin(source) { generated ->
                val flow = generated.getMethod("batchFlow").invoke(null) as MuseFlow.Flow
                val operation = flow.pipeline.single() as DbBulkInsertConfig
                val row = (operation.batchParameters as DbBatchParameterSource.Values).values.single()
                assertEquals(8081, row["int"])
                assertEquals(8082L, row["long"])
                assertNumericTypes(row, source)
                assertEquals(FLOAT_NAN.toRawBits(), (row.getValue("float") as Float).toRawBits())
                assertEquals(DOUBLE_NAN.toRawBits(), (row.getValue("double") as Double).toRawBits())
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, originalLocale)
        }
    }

    @Test
    fun `compiled batch and in-out parameter maps preserve integer and long value types`() {
        val values = numericValues(1, 2L)
        val batchConfig =
            DbBulkInsertConfig(
                configRef = DbConfigRef.ByName("database"),
                sql = "INSERT INTO numbers VALUES (:int, :long)",
                batchParameters = DbBatchParameterSource.Values(listOf(values)),
            )
        val storedProcedureConfig =
            DbStoredProcedureConfig(
                configRef = DbConfigRef.ByName("database"),
                sql = "CALL numeric_values(:int, :long)",
                inOutParameters = DbParameterSource.Values(values),
            )
        val source = generatedOperationSource(batchConfig, storedProcedureConfig)

        withCompiledKotlin(source) { generated ->
            val batchFlow = generated.getMethod("batchFlow").invoke(null) as MuseFlow.Flow
            val batchOperation = batchFlow.pipeline.single() as DbBulkInsertConfig
            val batchValues = (batchOperation.batchParameters as DbBatchParameterSource.Values).values.single()
            val procedureFlow = generated.getMethod("procedureFlow").invoke(null) as MuseFlow.Flow
            val procedure = procedureFlow.pipeline.single() as DbStoredProcedureConfig
            val inOutValues = (procedure.inOutParameters as DbParameterSource.Values).values

            assertAll(
                { assertNumericTypes(batchValues, source) },
                { assertNumericTypes(inOutValues, source) },
            )
        }
    }

    private fun assertNumericTypes(values: Map<*, *>, source: String) {
        assertAll(
            { assertEquals(Int::class.javaObjectType.name, values["int"]?.javaClass?.name, source) },
            { assertEquals(Long::class.javaObjectType.name, values["long"]?.javaClass?.name, source) },
        )
    }

    private fun numericValues(
        intValue: Int,
        longValue: Long,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            put("int", intValue)
            put("long", longValue)
        }

    private fun numericValuesWithNonFinite(
        intValue: Int,
        longValue: Long,
    ): Map<String, Any?> =
        numericValues(intValue, longValue).toMutableMap().apply {
            put("float", FLOAT_NAN)
            put("double", DOUBLE_NAN)
        }

    private fun generatedOperationSource(
        batch: DbBulkInsertConfig,
        storedProcedure: DbStoredProcedureConfig? = null,
    ): String =
        FileSpec.builder(GENERATED_PACKAGE, "GeneratedNumericValues")
            .addFunction(
                operationFlow(
                    "batchFlow",
                    DbBulkInsertCodeEmitter.emit(batch, CodegenContext(GENERATED_PACKAGE)),
                ),
            ).apply {
                storedProcedure?.let { procedure ->
                    addFunction(
                        operationFlow(
                            "procedureFlow",
                            DbStoredProcedureCodeEmitter.emit(procedure, CodegenContext(GENERATED_PACKAGE)),
                        ),
                    )
                }
            }
            .build()
            .toString()

    private fun operationFlow(
        functionName: String,
        operation: CodeBlock,
    ): FunSpec =
        FunSpec.builder(functionName)
            .returns(MuseFlow.Flow::class)
            .addCode("return %M(%S) {\n", MemberName("dev.museframework.dsl.dsl", "flow"), functionName)
            .addCode(operation)
            .addCode("}\n")
            .build()

    private fun withCompiledKotlin(
        source: String,
        verify: (Class<*>) -> Unit,
    ) {
        val temporaryDirectory = Files.createTempDirectory("db-generated-kotlin-")
        try {
            val sourceFile = temporaryDirectory.resolve("GeneratedNumericValues.kt")
            val outputDirectory = Files.createDirectory(temporaryDirectory.resolve("classes"))
            Files.writeString(sourceFile, source)

            val compilerOutput = ByteArrayOutputStream()
            val compilerClasspath =
                System.getProperty("surefire.test.class.path")
                    ?: System.getProperty("java.class.path")
            val result =
                K2JVMCompiler().exec(
                    PrintStream(compilerOutput),
                    "-no-stdlib",
                    "-no-reflect",
                    "-classpath",
                    requireNotNull(compilerClasspath),
                    "-jvm-target",
                    "21",
                    "-d",
                    outputDirectory.toString(),
                    sourceFile.toString(),
                )
            assertEquals(ExitCode.OK, result, compilerOutput.toString(Charsets.UTF_8.name()))

            URLClassLoader(arrayOf(outputDirectory.toUri().toURL()), javaClass.classLoader).use { classLoader ->
                val generated =
                    Class.forName(
                        "$GENERATED_PACKAGE.GeneratedNumericValuesKt",
                        true,
                        classLoader,
                    )
                verify(generated)
            }
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
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

    private companion object {
        const val GENERATED_PACKAGE = "dev.museframework.babelfish.mappers.db.generated"
        val FLOAT_NAN: Float = Float.fromBits(0x7fc00001)
        val DOUBLE_NAN: Double = Double.fromBits(0x7ff8000000000001L)
    }
}

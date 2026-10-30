package dev.museframework.connectors.sftp.processor

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.engine.api.streaming.MuseCursorStreamProvider
import dev.museframework.connectors.sftp.client.MuseSftpClient
import dev.museframework.connectors.sftp.client.SftpClientFailure
import dev.museframework.connectors.sftp.client.SftpReadHandle
import dev.museframework.sftp.api.config.*
import dev.museframework.sftp.api.model.SftpErrorType
import dev.museframework.sftp.api.model.SftpFileAttributes
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpTransferRequest
import dev.museframework.sftp.api.model.SftpWriteMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SftpOperationProcessorContractIT {
    private val connection =
        SftpConnectionConfig(
            host = "localhost",
            username = "muse",
            password = "secret",
            hostKeyChecking = SftpHostKeyChecking.INSECURE,
        )
    private val connectionRef = SftpConnectionRef.ByReference(connection)
    private val attributes = fileAttributes("/inbound/orders.csv")

    @Test
    fun `dynamic write mode is resolved for each current event and overrides the static mode`() = runTest {
        val client = RecordingClient()
        val operation = SftpWriteConfig(
            connection = connectionRef,
            path = "/outbound/data",
            mode = SftpWriteMode.APPEND,
            modeExpression = "#[vars.sftpWriteMode]",
        )
        val evaluator = object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? =
                if (expression == "#[vars.sftpWriteMode]") event.variables["sftpWriteMode"] else TypedValue.of(expression)
        }
        val processor = processor(operation, client, evaluator)
        for (mode in listOf<Any>("OVERWRITE", " append ", SftpWriteMode.CREATE_NEW)) {
            val event = MuseEvent(payload = TypedValue.of("row"), variables = mapOf("sftpWriteMode" to TypedValue.of(mode)))
            assertIs<ProcessorResult.Success>(processor.process(context(event)))
            assertEquals(if (mode is SftpWriteMode) mode else SftpWriteMode.valueOf(mode.toString().trim().uppercase()), client.writeMode)
        }
    }

    @Test
    fun `invalid dynamic modes fail before path content and cursor evaluation without static fallback`() = runTest {
        for (mode in listOf(null, "INVALID", 42)) {
            val provider = TestStreamProvider("payload".toByteArray())
            val client = RecordingClient()
            val calls = mutableListOf<String>()
            val evaluator = object : MuseExpressionEvaluator {
                override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? {
                    calls += expression
                    return TypedValue.of(mode)
                }
            }
            val operation = SftpWriteConfig(
                connection = connectionRef, path = "#[vars.path]", content = "#[payload]",
                modeExpression = "#[vars.sftpWriteMode]",
            )
            val result = assertIs<ProcessorResult.Failure>(processor(operation, client, evaluator)
                .process(context(MuseEvent(payload = TypedValue.of(provider)))))
            assertEquals(SftpErrorType.OPERATION_FAILED, result.error.errorType)
            assertEquals(listOf("#[vars.sftpWriteMode]"), calls)
            assertEquals(0, provider.openCount)
            assertEquals(null, client.writePath)
        }
    }

    @Test
    fun `list evaluates the path and exposes typed file metadata`() =
        runTest {
            val client = RecordingClient().apply { listedFiles = listOf(attributes) }
            val operation =
                SftpListConfig(
                    connection = connectionRef,
                    directoryPath = "#[vars.directory]",
                    recursive = true,
                    matcher = SftpFileMatcher(filenamePattern = "*.csv"),
                )
            val result =
                assertIs<ProcessorResult.Success>(
                    processor(operation, client, mapOf("#[vars.directory]" to "/inbound"))
                        .process(context()),
                )

            assertEquals("/inbound", client.listDirectory)
            assertEquals(true, client.listRecursive)
            assertEquals(listOf(attributes), result.context.event.payload?.getOrNull())
            assertEquals(null, result.context.event.attributes)
        }

    @Test
    fun `read returns a managed repeatable remote stream and attributes`() =
        runTest {
            val client = RecordingClient().apply { statResult = attributes; readBytes = "orders".toByteArray() }
            val operation = SftpReadConfig(connection = connectionRef, path = "/inbound/orders.csv")
            val executionContext = context()
            val result =
                assertIs<ProcessorResult.Success>(processor(operation, client).process(executionContext))

            val provider = assertIs<MuseCursorStreamProvider>(result.context.event.payload?.getOrNull())
            assertEquals(attributes.size, provider.size)
            assertEquals(attributes, result.context.event.attributes?.getOrNull())
            assertTrue(provider.isManaged)
            assertEquals("orders", provider.openCursor().use { it.readAllBytes().toString(Charsets.UTF_8) })
            assertEquals("orders", provider.openCursor().use { it.readAllBytes().toString(Charsets.UTF_8) })
            assertEquals(2, client.readCount)

            executionContext.streamingResources.releaseAll()
            assertTrue(provider.isClosed)
            assertEquals(2, client.closedReadHandles)
        }

    @Test
    fun `read projects the payload into the target variable`() =
        runTest {
            val client = RecordingClient().apply { statResult = attributes; readBytes = "orders".toByteArray() }
            val target = TargetConfig.of("remoteFile")
            val operation = SftpReadConfig(connection = connectionRef, path = "/file", targetConfig = target)
            val executionContext = context()

            val result = assertIs<ProcessorResult.Success>(processor(operation, client).process(executionContext))

            val provider =
                assertIs<MuseCursorStreamProvider>(result.context.event.variables["remoteFile"]?.getOrNull())
            assertEquals("orders", provider.openCursor().use { it.readAllBytes().toString(Charsets.UTF_8) })
            executionContext.streamingResources.releaseAll()
        }

    @Test
    fun `write streams an evaluated provider without materializing it`() =
        runTest {
            val client = RecordingClient()
            val payloadProvider = TestStreamProvider("payload-stream".toByteArray())
            val operation =
                SftpWriteConfig(
                    connection = connectionRef,
                    path = "#[vars.path]",
                    content = "#[vars.content]",
                    createParentDirectories = false,
                    mode = SftpWriteMode.APPEND,
                )
            val evaluator =
                StubEvaluator(
                    mapOf(
                        "#[vars.path]" to "/out/orders.csv",
                        "#[vars.content]" to payloadProvider,
                    ),
                )
            val originalEvent = MuseEvent(payload = TypedValue.of("original"))
            val executionContext = context(originalEvent, evaluator)

            val result = assertIs<ProcessorResult.Success>(processor(operation, client, evaluator).process(executionContext))

            assertSame(executionContext, result.context)
            assertEquals("/out/orders.csv", client.writePath)
            assertContentEquals("payload-stream".toByteArray(), client.writtenBytes)
            assertEquals(SftpWriteMode.APPEND, client.writeMode)
            assertEquals(false, client.createParents)
            assertEquals(1, payloadProvider.openCount)
        }

    @Test
    fun `write modes create new and overwrite are forwarded to the client`() =
        runTest {
            val client = RecordingClient()
            val createNew =
                SftpWriteConfig(
                    connection = connectionRef,
                    path = "/out/new.csv",
                    content = "#[payload]",
                    mode = SftpWriteMode.CREATE_NEW,
                    createParentDirectories = true,
                )
            val overwrite =
                SftpWriteConfig(
                    connection = connectionRef,
                    path = "/out/existing.csv",
                    content = "#[payload]",
                    mode = SftpWriteMode.OVERWRITE,
                    createParentDirectories = false,
                )

            assertIs<ProcessorResult.Success>(
                processor(createNew, client, mapOf("#[payload]" to "fresh")).process(context()),
            )
            assertEquals(SftpWriteMode.CREATE_NEW, client.writeMode)
            assertEquals(true, client.createParents)

            assertIs<ProcessorResult.Success>(
                processor(overwrite, client, mapOf("#[payload]" to "fresh")).process(context()),
            )
            assertEquals(SftpWriteMode.OVERWRITE, client.writeMode)
            assertEquals(false, client.createParents)
            assertEquals("/out/existing.csv", client.writePath)
        }

    @Test
    fun `list target projection and matcher are forwarded to the client`() =
        runTest {
            val attributes = fileAttributes("/inbound/orders.csv")
            val client = RecordingClient().apply { listedFiles = listOf(attributes) }
            val matcher = SftpFileMatcher(filenamePattern = "*.csv")
            val operation =
                SftpListConfig(
                    connection = connectionRef,
                    directoryPath = "/inbound",
                    recursive = true,
                    matcher = matcher,
                    targetConfig = TargetConfig.of("listing"),
                )

            val result = assertIs<ProcessorResult.Success>(processor(operation, client).process(context()))

            assertEquals("/inbound", client.listDirectory)
            assertEquals(true, client.listRecursive)
            assertEquals(matcher, client.listMatcher)
            assertEquals(listOf(attributes), result.context.event.variables["listing"]?.getOrNull())
        }

    @Test
    fun `write uses the payload when content is omitted`() =
        runTest {
            val client = RecordingClient()
            val operation = SftpWriteConfig(connection = connectionRef, path = "/out/from-payload.csv")

            assertIs<ProcessorResult.Success>(processor(operation, client).process(context()))

            assertContentEquals("payload".toByteArray(), client.writtenBytes)
            assertEquals("/out/from-payload.csv", client.writePath)
        }

    @Test
    fun `write accepts byte array and input stream content`() =
        runTest {
            val client = RecordingClient()
            val bytes = byteArrayOf(9, 8, 7)
            val binary =
                SftpWriteConfig(
                    connection = connectionRef,
                    path = "/out/bytes.bin",
                    content = "#[vars.content]",
                )
            assertIs<ProcessorResult.Success>(
                processor(binary, client, mapOf("#[vars.content]" to bytes)).process(context()),
            )
            assertContentEquals(bytes, client.writtenBytes)

            val stream = ByteArrayInputStream(byteArrayOf(1, 2))
            assertIs<ProcessorResult.Success>(
                processor(binary, client, mapOf("#[vars.content]" to stream)).process(context()),
            )
            assertContentEquals(byteArrayOf(1, 2), client.writtenBytes)
        }

    @Test
    fun `read of a directory fails before opening a stream`() =
        runTest {
            val client =
                RecordingClient().apply {
                    statResult = attributes.copy(directory = true, regularFile = false)
                }
            val operation = SftpReadConfig(connection = connectionRef, path = "/inbound")

            val result = assertIs<ProcessorResult.Failure>(processor(operation, client).process(context()))

            assertEquals(SftpErrorType.IS_A_DIRECTORY, result.error.errorType)
            assertEquals(0, client.readCount)
        }

    @Test
    fun `unexpected client exceptions become operation failed`() =
        runTest {
            val boom = IllegalStateException("boom")
            val client = RecordingClient().apply { unexpected = boom }
            val operation = SftpReadConfig(connection = connectionRef, path = "/inbound/orders.csv")

            val result = assertIs<ProcessorResult.Failure>(processor(operation, client).process(context()))

            assertEquals(SftpErrorType.OPERATION_FAILED, result.error.errorType)
            val clientFailure = assertIs<SftpClientFailure>(result.error.cause)
            val thrown = assertIs<IllegalStateException>(clientFailure.cause)
            assertEquals("boom", thrown.message)
        }

    @Test
    fun `cancellation propagates instead of becoming an operation failure`() =
        runTest {
            val client = RecordingClient().apply { unexpected = CancellationException("cancelled") }
            val operation = SftpDeleteConfig(connection = connectionRef, path = "/inbound/orders.csv")

            val thrown =
                assertFailsWith<CancellationException> {
                    processor(operation, client).process(context())
                }

            assertEquals("cancelled", thrown.message)
        }

    @Test
    fun `a null path expression is forwarded as the expression text`() =
        runTest {
            val client = RecordingClient()
            val operation = SftpDeleteConfig(connection = connectionRef, path = "#[vars.path]")
            val evaluator = StubEvaluator(mapOf("#[vars.path]" to null))

            assertIs<ProcessorResult.Success>(processor(operation, client, evaluator).process(context()))

            assertEquals("#[vars.path]", client.deletedPath)
        }

    @Test
    fun `write rejects an explicitly null content expression instead of falling back to payload`() =
        runTest {
            val client = RecordingClient()
            val operation =
                SftpWriteConfig(
                    connection = connectionRef,
                    path = "/out/orders.csv",
                    content = "#[vars.content]",
                )
            val evaluator = StubEvaluator(mapOf("#[vars.content]" to null))

            val result = assertIs<ProcessorResult.Failure>(processor(operation, client, evaluator).process(context()))

            assertEquals(SftpErrorType.OPERATION_FAILED, result.error.errorType)
            assertEquals(null, client.writtenBytes)
        }

    @Test
    fun `write rejects unrecognized payload types as ILLEGAL_CONTENT`() =
        runTest {
            val client = RecordingClient()
            val operation =
                SftpWriteConfig(
                    connection = connectionRef,
                    path = "/out/orders.csv",
                    content = "#[vars.content]",
                )

            val result =
                assertIs<ProcessorResult.Failure>(
                    processor(operation, client, mapOf("#[vars.content]" to listOf("a", "b"))).process(context()),
                )

            assertEquals(SftpErrorType.ILLEGAL_CONTENT, result.error.errorType)
            assertEquals(null, client.writtenBytes)
        }

    @Test
    fun `mutation operations evaluate paths and preserve the event`() =
        runTest {
            val client = RecordingClient()
            val evaluator =
                StubEvaluator(
                    mapOf(
                        "#[vars.source]" to "/source/file.txt",
                        "#[vars.target]" to "/target",
                        "#[vars.name]" to "renamed.txt",
                    ),
                )
            val event = MuseEvent(payload = TypedValue.of("unchanged"))
            val operations =
                listOf(
                    SftpCopyConfig(connection = connectionRef, sourcePath = "#[vars.source]", targetDirectory = "#[vars.target]", renameTo = "#[vars.name]", overwrite = true),
                    SftpMoveConfig(connection = connectionRef, sourcePath = "#[vars.source]", targetDirectory = "#[vars.target]", renameTo = "#[vars.name]"),
                    SftpRenameConfig(connection = connectionRef, path = "#[vars.source]", newName = "#[vars.name]", overwrite = true),
                    SftpDeleteConfig(connection = connectionRef, path = "#[vars.source]"),
                    SftpCreateDirectoryConfig(connection = connectionRef, directoryPath = "#[vars.target]"),
                )

            operations.forEach { operation ->
                val executionContext = context(event, evaluator)
                val result = assertIs<ProcessorResult.Success>(processor(operation, client, evaluator).process(executionContext))
                assertSame(executionContext, result.context)
            }

            assertEquals(CopyCall("/source/file.txt", "/target", true, true, "renamed.txt"), client.copyCall)
            assertEquals(CopyCall("/source/file.txt", "/target", true, false, "renamed.txt"), client.moveCall)
            assertEquals(Triple("/source/file.txt", "renamed.txt", true), client.renameCall)
            assertEquals("/source/file.txt", client.deletedPath)
            assertEquals("/target", client.createdDirectory)
        }

    @Test
    fun `client failures become typed Muse failures for read write and mutation operations`() =
        runTest {
            val cause = SftpClientFailure(SftpErrorType.ACCESS_DENIED, "denied")
            val client = RecordingClient().apply { failure = cause }
            val operations =
                listOf(
                    SftpReadConfig(connection = connectionRef, path = "/secret"),
                    SftpWriteConfig(connection = connectionRef, path = "/secret", content = "#[payload]"),
                    SftpCopyConfig(connection = connectionRef, sourcePath = "/a", targetDirectory = "/b"),
                    SftpMoveConfig(connection = connectionRef, sourcePath = "/a", targetDirectory = "/b"),
                    SftpRenameConfig(connection = connectionRef, path = "/a", newName = "b"),
                    SftpDeleteConfig(
                        connection = connectionRef,
                        path = "/secret",
                        sourceFile = "Flows.kt",
                        sourceLine = 27,
                        sourceClass = "example.Flows",
                    ),
                    SftpCreateDirectoryConfig(connection = connectionRef, directoryPath = "/secret"),
                    SftpListConfig(connection = connectionRef, directoryPath = "/secret"),
                )

            operations.forEach { operation ->
                val result = assertIs<ProcessorResult.Failure>(processor(operation, client).process(context()))
                assertEquals(SftpErrorType.ACCESS_DENIED, result.error.errorType)
                assertSame(result.error, result.context.event.error)
            }

            val located =
                assertIs<ProcessorResult.Failure>(
                    processor(
                        SftpDeleteConfig(
                            connection = connectionRef,
                            path = "/missing",
                            sourceFile = "Flows.kt",
                            sourceLine = 27,
                            sourceClass = "example.Flows",
                        ),
                        client,
                    ).process(context()),
                )
            assertEquals("Flows.kt", located.error.sourceFile)
            assertEquals(27, located.error.sourceLine)
            assertEquals("example.Flows", located.error.sourceClass)
        }

    private fun processor(
        operation: SftpOperationConfig,
        client: MuseSftpClient,
        values: Map<String, Any?> = emptyMap(),
    ) = processor(operation, client, StubEvaluator(values))

    private fun processor(
        operation: SftpOperationConfig,
        client: MuseSftpClient,
        evaluator: MuseExpressionEvaluator,
    ) = SftpOperationMuseProcessor(operation, client, evaluator)

    private fun TestScope.context(
        event: MuseEvent = MuseEvent(payload = TypedValue.of("payload")),
        evaluator: MuseExpressionEvaluator = StubEvaluator(),
    ) = ExecutionContext(
        event = event,
        errorHandler = null,
        coroutineScope = this,
        expressionEvaluator = evaluator,
    )

    private class StubEvaluator(
        private val values: Map<String, Any?> = emptyMap(),
    ) : MuseExpressionEvaluator {
        override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? =
            TypedValue.of(if (values.containsKey(expression)) values[expression] else expression)
    }

    private class RecordingClient : MuseSftpClient {
        var listedFiles: List<SftpFileAttributes> = emptyList()
        var statResult: SftpFileAttributes = fileAttributes("/file")
        var readBytes: ByteArray = "content".toByteArray()
        var failure: SftpClientFailure? = null
        var unexpected: Throwable? = null
        var listDirectory: String? = null
        var listRecursive: Boolean? = null
        var listMatcher: SftpFileMatcher? = null
        var readCount = 0
        var closedReadHandles = 0
        var writePath: String? = null
        var writtenBytes: ByteArray? = null
        var writeMode: SftpWriteMode? = null
        var createParents: Boolean? = null
        var copyCall: CopyCall? = null
        var moveCall: CopyCall? = null
        var deletedPath: String? = null
        var renameCall: Triple<String, String, Boolean>? = null
        var createdDirectory: String? = null

        override fun stat(path: String): SftpFileAttributes {
            failIfNeeded()
            return statResult
        }

        override fun list(directoryPath: String, recursive: Boolean, matcher: SftpFileMatcher): List<SftpFileAttributes> {
            failIfNeeded()
            listDirectory = directoryPath
            listRecursive = recursive
            listMatcher = matcher
            return listedFiles
        }

        private fun failIfNeeded() {
            unexpected?.let { throw it }
            failure?.let { throw it }
        }

        override fun openRead(path: String): SftpReadHandle {
            failIfNeeded()
            readCount++
            return SftpReadHandle(ByteArrayInputStream(readBytes), statResult) { closedReadHandles++ }
        }

        override fun write(path: String, content: InputStream, mode: SftpWriteMode, createParentDirectories: Boolean) {
            failIfNeeded()
            writePath = path
            writtenBytes = content.readAllBytes()
            writeMode = mode
            createParents = createParentDirectories
        }

        override fun copy(transfer: SftpTransferRequest) {
            failIfNeeded()
            copyCall = CopyCall(
                transfer.sourcePath,
                transfer.targetDirectory,
                transfer.createParentDirectories,
                transfer.overwrite,
                transfer.renameTo,
            )
        }

        override fun move(transfer: SftpTransferRequest) {
            failIfNeeded()
            moveCall = CopyCall(
                transfer.sourcePath,
                transfer.targetDirectory,
                transfer.createParentDirectories,
                transfer.overwrite,
                transfer.renameTo,
            )
        }

        override fun delete(path: String) {
            failIfNeeded()
            deletedPath = path
        }

        override fun rename(path: String, newName: String, overwrite: Boolean) {
            failIfNeeded()
            renameCall = Triple(path, newName, overwrite)
        }

        override fun createDirectory(path: String) {
            failIfNeeded()
            createdDirectory = path
        }
    }

    private class TestStreamProvider(
        private val bytes: ByteArray,
    ) : MuseCursorStreamProvider {
        var openCount = 0
        override val size: Long = bytes.size.toLong()
        override var isClosed: Boolean = false
            private set

        override fun openCursor() =
            dev.museframework.engine.api.streaming.NonRepeatableCursorStreamProvider(ByteArrayInputStream(bytes))
                .also { openCount++ }
                .openCursor()

        override fun close() {
            isClosed = true
        }

        override fun releaseResources() {
            isClosed = true
        }
    }

    private data class CopyCall(
        val source: String,
        val target: String,
        val createParents: Boolean,
        val overwrite: Boolean,
        val renameTo: String?,
    )

    companion object {
        private fun fileAttributes(path: String) =
            SftpFileAttributes(
                path = path,
                name = path.substringAfterLast('/'),
                size = 6,
                modifiedAt = Instant.parse("2026-08-01T12:00:00Z"),
                regularFile = true,
                directory = false,
                symbolicLink = false,
                permissions = 0b110100100,
            )
    }
}

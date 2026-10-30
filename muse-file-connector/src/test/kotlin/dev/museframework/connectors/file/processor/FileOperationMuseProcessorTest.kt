package dev.museframework.connectors.file.processor

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.file.api.config.*
import dev.museframework.file.api.model.FileErrorType
import dev.museframework.file.api.model.FileMessage
import dev.museframework.file.api.model.FileWriteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileOperationMuseProcessorTest {
    @TempDir
    lateinit var workingDirectory: Path

    private val evaluator =
        object : MuseExpressionEvaluator {
            override fun evaluate(expression: String, event: MuseEvent): TypedValue<Any>? =
                when (expression) {
                    "#[payload]" -> event.payload
                    "#[vars.path]" -> event.variables["path"]
                    else -> TypedValue.of(expression as Any)
                }
        }

    private fun connection() = FileConnectionConfig(workingDirectory = workingDirectory.toString())

    private fun context(event: MuseEvent = MuseEvent()): ExecutionContext =
        ExecutionContext(
            event = event,
            errorHandler = null,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            expressionEvaluator = evaluator,
        )

    private fun processor(component: dev.museframework.dsl.api.model.component.Processor) =
        FileOperationMuseProcessor(component, connection(), evaluator)

    @Test
    fun `write consumes the current binary payload and read returns streaming content and attributes`() = runTest {
        val original = "streamed-content".toByteArray()
        val write =
            FileWriteConfig(
                connectionConfig = ConfigRef.ByReference(connection()),
                path = "nested/value.txt",
            )

        val writeResult = processor(write).process(context(MuseEvent(payload = TypedValue.of(original as Any))))

        assertIs<ProcessorResult.Success>(writeResult)
        assertContentEquals(original, workingDirectory.resolve("nested/value.txt").readBytes())

        val read =
            FileReadConfig(
                connectionConfig = ConfigRef.ByReference(connection()),
                path = "nested/value.txt",
                outputMimeType = "text/plain",
            )
        val readResult = assertIs<ProcessorResult.Success>(processor(read).process(context()))
        val provider = readResult.context.event.payload!!.asCursorStreamProvider()!!

        assertEquals("streamed-content", provider.openCursor().bufferedReader().use { it.readText() })
        assertEquals("text/plain", readResult.context.event.payload!!.mimeType)
        val attributes =
            assertIs<dev.museframework.file.api.model.LocalFileAttributes>(
                readResult.context.event.attributes!!.value,
            )
        assertEquals("value.txt", attributes.fileName)
        assertEquals(original.size.toLong(), attributes.size)
        readResult.context.streamingResources.releaseAll()
    }

    @Test
    fun `write evaluates paths and supports append mode`() = runTest {
        workingDirectory.resolve("value.txt").writeText("first")
        val event =
            MuseEvent(
                payload = TypedValue.of("-second" as Any),
                variables = mapOf("path" to TypedValue.of("value.txt" as Any)),
            )
        val operation =
            FileWriteConfig(
                connectionConfig = ConfigRef.ByReference(connection()),
                path = "#[vars.path]",
                mode = FileWriteMode.APPEND,
            )

        processor(operation).process(context(event))

        assertEquals("first-second", workingDirectory.resolve("value.txt").readText())
    }

    @Test
    fun `create new write maps an existing file to FILE_ALREADY_EXISTS`() = runTest {
        workingDirectory.resolve("value.txt").writeText("existing")
        val operation =
            FileWriteConfig(
                connectionConfig = ConfigRef.ByReference(connection()),
                path = "value.txt",
                content = "replacement",
                mode = FileWriteMode.CREATE_NEW,
            )

        val result = assertIs<ProcessorResult.Failure>(processor(operation).process(context()))

        assertEquals(FileErrorType.FILE_ALREADY_EXISTS, result.error.errorType)
        assertEquals("existing", workingDirectory.resolve("value.txt").readText())
    }

    @Test
    fun `read maps missing paths and preserves source metadata`() = runTest {
        val operation =
            FileReadConfig(
                docName = "missing read",
                sourceFile = "Flow.kt",
                sourceLine = 42,
                sourceClass = "example.Flow",
                connectionConfig = ConfigRef.ByReference(connection()),
                path = "missing.txt",
            )

        val result = assertIs<ProcessorResult.Failure>(processor(operation).process(context()))

        assertEquals(FileErrorType.ILLEGAL_PATH, result.error.errorType)
        assertEquals("Flow.kt", result.error.sourceFile)
        assertEquals(42, result.error.sourceLine)
        assertEquals("example.Flow", result.error.sourceClass)
    }

    @Test
    fun `read honors target configuration without losing the original event`() = runTest {
        workingDirectory.resolve("value.txt").writeText("value")
        val event = MuseEvent(payload = TypedValue.of("original" as Any))
        val operation =
            FileReadConfig(
                connectionConfig = ConfigRef.ByReference(connection()),
                path = "value.txt",
                targetConfig = TargetConfig("file"),
            )

        val result = assertIs<ProcessorResult.Success>(processor(operation).process(context(event)))

        assertEquals(event.correlationId, result.context.event.correlationId)
        assertEquals("original", result.context.event.payload!!.value)
        assertTrue(result.context.event.variables.containsKey("file"))
        result.context.streamingResources.releaseAll()
    }

    @Test
    fun `list returns file messages and registers their providers`() = runTest {
        workingDirectory.resolve("one.txt").writeText("one")
        val operation =
            FileListConfig(
                connectionConfig = ConfigRef.ByReference(connection()),
                directoryPath = ".",
            )

        val result = assertIs<ProcessorResult.Success>(processor(operation).process(context()))
        val messages = assertIs<List<FileMessage>>(result.context.event.payload!!.value)

        assertEquals(listOf("one.txt"), messages.map { it.attributes.fileName })
        assertEquals("one", messages.single().payload!!.openCursor().bufferedReader().use { it.readText() })
        assertFalse(result.context.streamingResources.isReleased)
        result.context.streamingResources.releaseAll()
        assertTrue(messages.single().payload!!.isClosed)
    }

    @Test
    fun `mutating processors preserve the input event`() = runTest {
        workingDirectory.resolve("source.txt").writeText("value")
        val event = MuseEvent(payload = TypedValue.of("payload" as Any))
        var current = context(event)
        val operations =
            listOf(
                FileCreateDirectoryConfig(
                    connectionConfig = ConfigRef.ByReference(connection()),
                    directoryPath = "copy",
                ),
                FileCopyConfig(
                    connectionConfig = ConfigRef.ByReference(connection()),
                    sourcePath = "source.txt",
                    targetPath = "copy",
                ),
                FileRenameConfig(
                    connectionConfig = ConfigRef.ByReference(connection()),
                    path = "copy/source.txt",
                    to = "renamed.txt",
                ),
                FileMoveConfig(
                    connectionConfig = ConfigRef.ByReference(connection()),
                    sourcePath = "copy/renamed.txt",
                    targetPath = "archive",
                ),
                FileDeleteConfig(
                    connectionConfig = ConfigRef.ByReference(connection()),
                    path = "archive/renamed.txt",
                ),
            )

        operations.forEach { operation ->
            val result = assertIs<ProcessorResult.Success>(processor(operation).process(current))
            assertSame(event, result.context.event)
            current = result.context
        }

        assertFalse(workingDirectory.resolve("archive/renamed.txt").exists())
    }

    @Test
    fun `a locked read rejects a second overlapping lock`() = runTest {
        workingDirectory.resolve("locked.txt").writeText("value")
        val operation =
            FileReadConfig(
                connectionConfig = ConfigRef.ByReference(connection()),
                path = "locked.txt",
                lock = true,
            )
        val first = assertIs<ProcessorResult.Success>(processor(operation).process(context()))
        val held = first.context.event.payload!!.asCursorStreamProvider()!!.openCursor()

        val second = assertIs<ProcessorResult.Success>(processor(operation).process(context()))
        val overlap =
            assertFailsWith<dev.museframework.connectors.file.internal.FileConnectorException> {
                second.context.event.payload!!.asCursorStreamProvider()!!.openCursor()
            }

        assertEquals(FileErrorType.FILE_LOCK, overlap.errorType)
        held.close()
        first.context.streamingResources.releaseAll()
        second.context.streamingResources.releaseAll()
    }
}

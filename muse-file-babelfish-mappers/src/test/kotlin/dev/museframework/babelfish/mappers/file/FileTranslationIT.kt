package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.file.api.config.FileConnectionConfig
import dev.museframework.file.api.config.FileCopyConfig
import dev.museframework.file.api.config.FileCreateDirectoryConfig
import dev.museframework.file.api.config.FileDeleteConfig
import dev.museframework.file.api.config.FileListConfig
import dev.museframework.file.api.config.FileListenerConfig
import dev.museframework.file.api.config.FileMatcherConfig
import dev.museframework.file.api.config.FileMoveConfig
import dev.museframework.file.api.config.FileReadConfig
import dev.museframework.file.api.config.FileRenameConfig
import dev.museframework.file.api.config.FileWriteConfig
import dev.museframework.file.api.model.FileListSortCriteria
import dev.museframework.file.api.model.FileTypeRequirement
import dev.museframework.file.api.model.FileWriteMode
import dev.museframework.file.api.model.SortOrder
import dev.museframework.file.api.model.WatermarkMode
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTranslationIT {
    @Test
    fun `translates file read target into generated DSL`() {
        val result = translate("file-read-target.xml")
        val read = assertIs<FileReadConfig>(assertIs<MuseFlow.Flow>(result.flows.single()).pipeline.single())

        assertEquals(TargetConfig("stateText"), read.targetConfig)
        val code = emit(result)
        assertEquals("", read.encoding)
        assertContains(code, "encoding = \"\"")
        assertContains(code, "targetConfig = TargetConfig(\"stateText\")")
        assertFalse(code.contains("import java.util.concurrent.TimeUnit"))
    }

    @Test
    fun `translates and emits every file provider component category`() {
        val provider = FileMapperProvider()
        val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
        val result =
            MuseBabelfish.translateXmlToMuseKotlinDsl(
                Path.of("src/test/resources/mule/file/file-listener-operations.xml"),
                registry,
            )

        val connection = assertIs<FileConnectionConfig>(result.usedConfigs.getValue("File_Config"))
        assertEquals("File Config", connection.docName)
        assertEquals("/tmp/muse-file", connection.workingDirectory)
        assertEquals(25L, connection.timeBetweenSizeCheck)
        assertEquals(TimeUnit.MILLISECONDS, connection.timeBetweenSizeCheckUnit)

        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        assertEquals("file-flow-original", flow.docId)
        val listener = assertIs<FileListenerConfig>(flow.source)
        val listenerRef = assertIs<ConfigRef.ByName>(listener.connectionConfig)
        assertEquals("File_Config", listenerRef.name)
        assertEquals("inbox", listener.directory)
        assertEquals(false, listener.recursive)
        assertEquals(WatermarkMode.MODIFIED_TIMESTAMP, listener.watermarkMode)
        assertEquals(2_000L, listener.pollingFrequencyMillis)
        assertEquals(5_000L, listener.startDelayMillis)
        assertEquals(10L, listener.timeBetweenSizeCheck)
        assertEquals(TimeUnit.MILLISECONDS, listener.timeBetweenSizeCheckUnit)
        assertEquals("text/plain", listener.outputMimeType)
        assertEquals("UTF-8", listener.encoding)
        assertEquals(false, listener.autoDelete)
        assertEquals("archive", listener.moveToDirectory)
        assertEquals("processed.txt", listener.renameTo)
        assertEquals(false, listener.applyPostActionWhenFailed)
        assertEquals(true, listener.overwrite)
        assertEquals(FileTypeRequirement.REQUIRE, listener.matcher?.directories)
        assertEquals(Duration.ofSeconds(1), listener.matcher?.updatedInTheLast)

        val pipeline = flow.pipeline
        assertEquals(9, pipeline.size)

        val read = assertIs<FileReadConfig>(pipeline[0])
        assertEquals("inbox/input.txt", read.path)
        assertEquals(true, read.lock)
        assertEquals(5L, read.timeBetweenSizeCheck)
        assertEquals("text/plain", read.outputMimeType)
        assertEquals("UTF-8", read.encoding)

        val write = assertIs<FileWriteConfig>(pipeline[1])
        assertEquals("outbox/output.txt", write.path)
        assertEquals(FileWriteMode.APPEND, write.mode)
        assertEquals("#[payload]", write.content)

        val list = assertIs<FileListConfig>(pipeline[2])
        assertEquals("inbox", list.directoryPath)
        assertEquals(true, list.recursive)
        val namedMatcher = assertIs<FileMatcherConfig>(result.usedConfigs.getValue("smallFileMatcher"))
        assertEquals("*.txt", namedMatcher.matcher.filenamePattern)
        assertEquals(100L, namedMatcher.matcher.maxSize)
        assertEquals("*.txt", list.matcher?.filenamePattern)
        assertEquals(100L, list.matcher?.maxSize)
        assertEquals(5, list.subset?.limit)
        assertEquals(6, list.subset?.offset)
        assertEquals(FileListSortCriteria.DATE_CREATED, list.subset?.criteria)
        assertEquals(SortOrder.ASCENDING, list.subset?.order)

        val copy = assertIs<FileCopyConfig>(pipeline[3])
        assertEquals("inbox/input.txt", copy.sourcePath)
        assertEquals("copies", copy.targetPath)
        assertEquals("copied.txt", copy.renameTo)
        assertEquals(true, copy.overwrite)

        val move = assertIs<FileMoveConfig>(pipeline[4])
        assertEquals("inbox/staging.txt", move.sourcePath)
        assertEquals("moved", move.targetPath)
        assertEquals(false, move.createParentDirectories)

        val delete = assertIs<FileDeleteConfig>(pipeline[5])
        assertEquals("inbox/obsolete.txt", delete.path)

        val rename = assertIs<FileRenameConfig>(pipeline[6])
        assertEquals("inbox/old-name.txt", rename.path)
        assertEquals("new-name.txt", rename.to)
        assertEquals(true, rename.overwrite)

        val createDirectory = assertIs<FileCreateDirectoryConfig>(pipeline[7])
        assertEquals("inbox/created", createDirectory.directoryPath)

        val secondaryWrite = assertIs<FileWriteConfig>(pipeline[8])
        assertEquals("outbox/secondary.txt", secondaryWrite.path)

        assertAll(
            { assertEquals("file-connection-original", connection.docId) },
            { assertEquals("file-matcher-original", namedMatcher.docId) },
            { assertEquals("file-listener-original", listener.docId) },
            { assertEquals("file-read-original", read.docId) },
            { assertEquals("file-write-original", write.docId) },
            { assertEquals("file-list-original", list.docId) },
            { assertEquals("file-copy-original", copy.docId) },
            { assertEquals("file-move-original", move.docId) },
            { assertEquals("file-delete-original", delete.docId) },
            { assertEquals("file-rename-original", rename.docId) },
            { assertEquals("file-create-directory-original", createDirectory.docId) },
            {
                assertEquals(
                    "file-write-" + '"' + "secondary" + '"' + '-' + '$' + "batch",
                    secondaryWrite.docId,
                )
            },
        )

        val code =
            KotlinCodeEmitter()
                .apply { provider.registerEmitters(this) }
                .emit(result, "dev.museframework.babelfish.generated")

        val imports = code.lineSequence().filter { it.startsWith("import ") }.map { it.replace("`", "") }.toSet()
        val members = listOf(
            "fileConnection", "fileMatcher", "fileListener", "fileRead", "fileWrite", "fileList",
            "fileCopy", "fileMove", "fileDelete", "fileRename", "fileCreateDirectory",
        )
        for (member in members) assertContains(imports, "import dev.museframework.file.api.dsl.$member")
        assertContains(code, "fileConnection {")
        assertContains(code, "workingDirectory = \"/tmp/muse-file\"")
        assertContains(code, "fileListener(\"File_Config\")")
        assertContains(code, "directory = \"inbox\"")
        assertContains(code, "pollingFrequencyMillis = 2000")
        assertContains(code, "startDelayMillis = 5000")
        assertContains(code, "watermarkMode = WatermarkMode.MODIFIED_TIMESTAMP")
        assertContains(code, "fileRead(fileConnection(\"File_Config\"))")
        assertContains(code, "path = \"inbox/input.txt\"")
        assertContains(code, "mode = FileWriteMode.APPEND")
        assertContains(code, "content = \"#[payload]\"")
        assertContains(code, "fileMatcher {")
        assertContains(code, "name = \"smallFileMatcher\"")
        assertContains(code, "filenamePattern = \"*.txt\"")
        assertContains(code, "maxSize = 100")
        assertContains(code, "directories = FileTypeRequirement.REQUIRE")
        assertContains(code, "updatedInTheLast = Duration.ofMillis(1000L)")
        assertContains(code, "fileList(fileConnection(\"File_Config\"))")
        assertContains(code, "subset = LocalSubsetList(")
        assertContains(code, "criteria = FileListSortCriteria.DATE_CREATED")
        assertContains(code, "fileCopy(fileConnection(\"File_Config\"))")
        assertContains(code, "fileMove(fileConnection(\"File_Config\"))")
        assertContains(code, "fileDelete(fileConnection(\"File_Config\"))")
        assertContains(code, "fileRename(fileConnection(\"File_Config\"))")
        assertContains(code, "fileCreateDirectory(fileConnection(\"File_Config\"))")
        assertCodeRegionHasDocId(code, "fileConnection {", "fileMatcher {", connection.docId)
        assertCodeRegionHasDocId(
            code,
            "fileMatcher {",
            "fileListener(\"File_Config\")",
            namedMatcher.docId,
        )
        assertCallHasDocId(code, "fileListener(\"File_Config\")", listener.docId)
        assertCallHasDocId(code, "fileRead(fileConnection(\"File_Config\"))", read.docId)
        assertCallHasDocId(code, "fileWrite(fileConnection(\"File_Config\"))", write.docId)
        assertCallHasDocId(
            code,
            "fileWrite(fileConnection(\"File_Config\"))",
            secondaryWrite.docId,
            occurrence = 1,
        )
        assertCallHasDocId(code, "fileList(fileConnection(\"File_Config\"))", list.docId)
        assertCallHasDocId(code, "fileCopy(fileConnection(\"File_Config\"))", copy.docId)
        assertCallHasDocId(code, "fileMove(fileConnection(\"File_Config\"))", move.docId)
        assertCallHasDocId(code, "fileDelete(fileConnection(\"File_Config\"))", delete.docId)
        assertCallHasDocId(code, "fileRename(fileConnection(\"File_Config\"))", rename.docId)
        assertCallHasDocId(
            code,
            "fileCreateDirectory(fileConnection(\"File_Config\"))",
            createDirectory.docId,
        )
        assertFalse(code.replace("`", "").contains("dev.museframework.connectors.file"))
    }

    @Test
    fun `translates a common archive listener with csv matcher`() {
        val result = translate("file-common-archive-listener.xml")
        val connection = assertIs<FileConnectionConfig>(result.usedConfigs.getValue("File_Config"))
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val listener = assertIs<FileListenerConfig>(flow.source)
        assertTrue(runCatching { UUID.fromString(connection.docId) }.isSuccess)
        assertTrue(runCatching { UUID.fromString(listener.docId) }.isSuccess)
        assertNull(listener.sourceFile)
        assertNull(listener.sourceLine)
        assertNull(listener.sourceClass)
        assertNull(listener.docDescription)
        assertEquals("input", listener.directory)
        assertEquals("output", listener.moveToDirectory)
        assertEquals("*.csv", listener.matcher?.filenamePattern)
        assertEquals(1_000L, listener.pollingFrequencyMillis)

        val code = emit(result)
        assertContains(code, "docId = " + kotlinStringLiteral(connection.docId))
        assertCallHasDocId(code, "fileListener(\"File_Config\")", listener.docId)
        assertContains(code, "directory = \"input\"")
        assertContains(code, "moveToDirectory = \"output\"")
        assertContains(code, "filenamePattern = \"*.csv\"")
    }

    @Test
    fun `translates a common csv read with streaming mime type`() {
        val result = translate("file-common-read-csv.xml")
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val read = assertIs<FileReadConfig>(flow.pipeline.single())
        assertTrue(runCatching { UUID.fromString(read.docId) }.isSuccess)
        assertEquals("files/data.csv", read.path)
        assertEquals("application/csv; streaming=true", read.outputMimeType)

        val code = emit(result)
        assertCallHasDocId(code, "fileRead(fileConnection(\"File_Config\"))", read.docId)
        assertContains(code, "path = \"files/data.csv\"")
        assertContains(code, "outputMimeType = \"application/csv; streaming=true\"")
    }

    @Test
    fun `translates a common write with an expression path and append mode`() {
        val result = translate("file-common-write-expression.xml")
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val write = assertIs<FileWriteConfig>(flow.pipeline.single())
        assertEquals(
            "#['output_' ++ dw::Core::now() as String {format: 'yy_MM_dd'} ++ '.csv']",
            write.path,
        )
        assertEquals(FileWriteMode.APPEND, write.mode)
        assertEquals("#[payload]", write.content)

        val code = emit(result)
        assertContains(code, "mode = FileWriteMode.APPEND")
        assertContains(code, "content = \"#[payload]\"")
        assertContains(
            code,
            "path = \"#['output_' ++ dw::Core::now() as String {format: 'yy_MM_dd'} ++ '.csv']\"",
        )
    }

    @Test
    fun `translates a common list of the working directory`() {
        val result = translate("file-common-list.xml")
        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val list = assertIs<FileListConfig>(flow.pipeline.single())
        assertEquals("./", list.directoryPath)

        val code = emit(result)
        assertContains(code, "directoryPath = \"./\"")
    }

    private fun assertCallHasDocId(
        code: String,
        call: String,
        docId: String,
        occurrence: Int = 0,
    ) {
        val matches = Regex.escape(call).toRegex().findAll(code).toList()
        assertTrue(occurrence < matches.size, "Expected generated call #$occurrence for $call")

        val afterCall = code.substring(matches[occurrence].range.last + 1)
        val expectedAssignment = "docId = " + kotlinStringLiteral(docId)
        val expectedPrefix = Regex("^\\s*\\{\\s*" + Regex.escape(expectedAssignment))
        assertTrue(
            expectedPrefix.containsMatchIn(afterCall),
            "Expected $call #$occurrence to emit docId $docId in its own DSL block",
        )
    }

    private fun assertCodeRegionHasDocId(
        code: String,
        start: String,
        end: String,
        docId: String,
    ) {
        val startIndex = code.indexOf(start)
        assertTrue(startIndex >= 0, "Expected generated code to contain $start")
        val endIndex = code.indexOf(end, startIndex + start.length)
        assertTrue(endIndex > startIndex, "Expected $end after $start")
        val region = code.substring(startIndex, endIndex)
        assertContains(region, "docId = " + kotlinStringLiteral(docId))
    }

    private fun translate(fixture: String) =
        MuseBabelfish.translateXmlToMuseKotlinDsl(
            Path.of("src/test/resources/mule/file/$fixture"),
            BabelfishRegistry().apply { FileMapperProvider().registerMappings(this) },
        )

    private fun emit(result: TranslationResult): String =
        KotlinCodeEmitter()
            .apply { FileMapperProvider().registerEmitters(this) }
            .emit(result, "dev.museframework.babelfish.generated")
}

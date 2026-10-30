package dev.museframework.babelfish.mappers.sftp

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpCopyConfig
import dev.museframework.sftp.api.config.SftpCreateDirectoryConfig
import dev.museframework.sftp.api.config.SftpDeleteConfig
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.config.SftpListConfig
import dev.museframework.sftp.api.config.SftpListenerConfig
import dev.museframework.sftp.api.config.SftpMoveConfig
import dev.museframework.sftp.api.config.SftpReadConfig
import dev.museframework.sftp.api.config.SftpRenameConfig
import dev.museframework.sftp.api.config.SftpWriteConfig
import dev.museframework.sftp.api.model.SftpFileType
import dev.museframework.sftp.api.model.SftpWriteMode
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class SftpOperationMapperTest {
    private val registry = BabelfishRegistry().apply { SftpMapperProvider().registerMappings(this) }
    private val mappingContext = MappingContext(registry, SOURCE_FILE)

    @Test
    fun `write mode expressions remain dynamic in generated Kotlin`() {
        val mapped = SftpWriteMapper.map(
            operation("write", mapOf("config-ref" to "C", "path" to "/output", "mode" to "#[vars.sftpWriteMode]")),
            mappingContext,
        )
        val code = SftpWriteCodeEmitter.emit(mapped, CodegenContext("com.example"))
        assertContains(code, "modeExpression = \"#[vars.sftpWriteMode]\"")
        assertFalse(code.contains("SftpWriteMode.#["))
    }

    @Test
    fun `connection defaults to STRICT host key checking without known hosts`() {
        val config =
            SftpConnectionConfigMapper.map(
                RawConfig(
                    id = "id",
                    namespace = "sftp",
                    tag = "config",
                    attrs = mapOf("name" to "Sftp_Config"),
                    children =
                        listOf(
                            RawElement(
                                namespace = "sftp",
                                tag = "connection",
                                attrs =
                                    mapOf(
                                        "host" to "sftp.example.test",
                                        "username" to "muse",
                                        "password" to "secret",
                                    ),
                                children = emptyList(),
                                location = SourceLocation(1, 1),
                            ),
                        ),
                    location = SourceLocation(1, 1),
                ),
                mappingContext,
            )

        assertEquals(SftpHostKeyChecking.STRICT, config.hostKeyChecking)
        assertEquals("~/.ssh/known_hosts", config.knownHostsFile)
    }

    @Test
    fun `listener mapping and emission preserve CSV reader properties and documentation`() {
        val mapped = SftpListenerMapper.map(
            operation(
                "listener",
                mapOf(
                    "config-ref" to "Sftp_Config",
                    "outputMimeType" to "application/csv; separator=|; streaming=true; header=false",
                    "doc:name" to "Incoming CSV",
                    "doc:id" to "listener-csv-id",
                ),
                children = listOf(
                    RawElement(
                        namespace = "sftp",
                        tag = "non-repeatable-stream",
                        attrs = emptyMap(),
                        children = emptyList(),
                        location = SourceLocation(2, 1),
                    ),
                ),
            ),
            mappingContext,
        )

        assertEquals("application/csv; separator=|; streaming=true; header=false", mapped.outputMimeType)
        assertEquals("listener-csv-id", mapped.docId)
        assertEquals(true, mapped.nonRepeatable)
        val code = SftpListenerCodeEmitter.emit(mapped, CodegenContext("com.example"))
        assertContains(code, "outputMimeType = \"application/csv; separator=|; streaming=true; header=false\"")
        assertContains(code, "docName = \"Incoming CSV\"")
        assertContains(code, "docId = \"listener-csv-id\"")
        assertContains(code, "nonRepeatable = true")
    }

    @Test
    fun `listener defaults applyPostActionOnFailure to false`() {
        val mapped =
            SftpListenerMapper.map(
                RawElement(
                    namespace = "sftp",
                    tag = "listener",
                    attrs = mapOf("config-ref" to "Sftp_Config", "directory" to "inbox"),
                    children =
                        listOf(
                            RawElement(
                                namespace = "",
                                tag = "scheduling-strategy",
                                attrs = emptyMap(),
                                children =
                                    listOf(
                                        RawElement(
                                            namespace = "",
                                            tag = "fixed-frequency",
                                            attrs = mapOf("frequency" to "1000"),
                                            children = emptyList(),
                                            location = SourceLocation(2, 1),
                                        ),
                                    ),
                                location = SourceLocation(2, 1),
                            ),
                        ),
                    location = SourceLocation(1, 1),
                ),
                mappingContext,
            )

        assertIs<SftpListenerConfig>(mapped)
        assertEquals(false, mapped.applyPostActionOnFailure)
    }

    @Test
    fun `maps and emits every operation including targetPath fallback`() {
        val read =
            assertIs<SftpReadConfig>(
                SftpReadMapper.map(operation("read", mapOf("config-ref" to "Sftp_Config", "path" to "/a.txt")), mappingContext),
            )
        assertEquals("/a.txt", read.path)

        val write =
            assertIs<SftpWriteConfig>(
                SftpWriteMapper.map(
                    operation(
                        "write",
                        mapOf(
                            "config-ref" to "Sftp_Config",
                            "path" to "/out.txt",
                            "mode" to "APPEND",
                            "createParentDirectories" to "false",
                        ),
                        children =
                            listOf(
                                RawElement(
                                    namespace = "sftp",
                                    tag = "content",
                                    attrs = emptyMap(),
                                    children = emptyList(),
                                    location = SourceLocation(1, 1),
                                    textContent = "#[payload]",
                                ),
                            ),
                    ),
                    mappingContext,
                ),
            )
        assertEquals(SftpWriteMode.APPEND, write.mode)
        assertEquals(false, write.createParentDirectories)
        assertEquals("#[payload]", write.content)

        val list =
            assertIs<SftpListConfig>(
                SftpListMapper.map(
                    operation(
                        "list",
                        mapOf("config-ref" to "Sftp_Config", "directoryPath" to "/inbox"),
                        children =
                            listOf(
                                RawElement(
                                    namespace = "sftp",
                                    tag = "matcher",
                                    attrs =
                                        mapOf(
                                            "filenamePattern" to "regex:.*\\.csv",
                                            "directories" to "EXCLUDE",
                                            "symLinks" to "EXCLUDE",
                                        ),
                                    children = emptyList(),
                                    location = SourceLocation(1, 1),
                                ),
                            ),
                    ),
                    mappingContext,
                ),
            )
        assertEquals("regex:.*\\.csv", list.matcher.filenamePattern)
        assertEquals(SftpFileType.REGULAR_FILE, list.matcher.fileType)

        val copy =
            assertIs<SftpCopyConfig>(
                SftpCopyMapper.map(
                    operation(
                        "copy",
                        mapOf(
                            "config-ref" to "Sftp_Config",
                            "sourcePath" to "/src/a.txt",
                            "targetPath" to "/dst",
                            "overwrite" to "true",
                            "renameTo" to "b.txt",
                        ),
                    ),
                    mappingContext,
                ),
            )
        assertEquals("/dst", copy.targetDirectory)
        assertEquals(true, copy.overwrite)
        assertEquals("b.txt", copy.renameTo)

        val move =
            assertIs<SftpMoveConfig>(
                SftpMoveMapper.map(
                    operation(
                        "move",
                        mapOf(
                            "config-ref" to "Sftp_Config",
                            "sourcePath" to "/src/a.txt",
                            "targetDirectory" to "/archive",
                            "createParentDirectories" to "false",
                        ),
                    ),
                    mappingContext,
                ),
            )
        assertEquals("/archive", move.targetDirectory)
        assertEquals(false, move.createParentDirectories)

        assertIs<SftpDeleteConfig>(
            SftpDeleteMapper.map(operation("delete", mapOf("config-ref" to "Sftp_Config", "path" to "/gone.txt")), mappingContext),
        )
        assertEquals(
            "new.txt",
            assertIs<SftpRenameConfig>(
                SftpRenameMapper.map(
                    operation("rename", mapOf("config-ref" to "Sftp_Config", "path" to "/old.txt", "to" to "new.txt")),
                    mappingContext,
                ),
            ).newName,
        )
        assertEquals(
            "/new",
            assertIs<SftpCreateDirectoryConfig>(
                SftpCreateDirectoryMapper.map(
                    operation("create-directory", mapOf("config-ref" to "Sftp_Config", "directoryPath" to "/new")),
                    mappingContext,
                ),
            ).directoryPath,
        )

        val ctx = CodegenContext("com.example")
        assertContains(SftpReadCodeEmitter.emit(read, ctx), "path = \"/a.txt\"")
        assertContains(SftpWriteCodeEmitter.emit(write, ctx), "mode = SftpWriteMode.APPEND")
        assertContains(SftpListCodeEmitter.emit(list, ctx), "filenamePattern = \"regex:.*\\\\.csv\"")
        assertContains(SftpCopyCodeEmitter.emit(copy, ctx), "targetDirectory = \"/dst\"")
        assertContains(SftpCopyCodeEmitter.emit(copy, ctx), "renameTo = \"b.txt\"")
        assertContains(SftpMoveCodeEmitter.emit(move, ctx), "createParentDirectories = false")
        assertContains(
            SftpDeleteCodeEmitter.emit(
                SftpDeleteConfig(
                    connection = copy.connection,
                    path = "/gone.txt",
                ),
                ctx,
            ),
            "path = \"/gone.txt\"",
        )
        assertContains(
            SftpRenameCodeEmitter.emit(
                SftpRenameConfig(connection = copy.connection, path = "/old.txt", newName = "new.txt"),
                ctx,
            ),
            "newName = \"new.txt\"",
        )
        assertContains(
            SftpCreateDirectoryCodeEmitter.emit(
                SftpCreateDirectoryConfig(connection = copy.connection, directoryPath = "/new"),
                ctx,
            ),
            "directoryPath = \"/new\"",
        )
    }

    @Test
    fun `maps read and list target and targetValue from XML attributes`() {
        val read =
            assertIs<SftpReadConfig>(
                SftpReadMapper.map(
                    operation(
                        "read",
                        mapOf(
                            "config-ref" to "C",
                            "path" to "/a",
                            "target" to "download",
                            "targetValue" to "#[payload]",
                        ),
                    ),
                    mappingContext,
                ),
            )
        assertEquals(TargetConfig(target = "download", targetValue = "#[payload]"), read.targetConfig)

        val list =
            assertIs<SftpListConfig>(
                SftpListMapper.map(
                    operation(
                        "list",
                        mapOf(
                            "config-ref" to "C",
                            "directoryPath" to "/inbox",
                            "target" to "listing",
                            "targetValue" to "#[payload]",
                        ),
                    ),
                    mappingContext,
                ),
            )
        assertEquals(TargetConfig(target = "listing", targetValue = "#[payload]"), list.targetConfig)
    }

    @Test
    fun `maps read and list target-only with null targetValue`() {
        val read =
            assertIs<SftpReadConfig>(
                SftpReadMapper.map(
                    operation("read", mapOf("config-ref" to "C", "path" to "/a", "target" to "download")),
                    mappingContext,
                ),
            )
        assertEquals(TargetConfig(target = "download", targetValue = null), read.targetConfig)

        val list =
            assertIs<SftpListConfig>(
                SftpListMapper.map(
                    operation(
                        "list",
                        mapOf("config-ref" to "C", "directoryPath" to "/inbox", "target" to "listing"),
                    ),
                    mappingContext,
                ),
            )
        assertEquals(TargetConfig(target = "listing", targetValue = null), list.targetConfig)
    }

    @Test
    fun `maps read and list without target leave targetConfig null`() {
        val read =
            assertIs<SftpReadConfig>(
                SftpReadMapper.map(
                    operation("read", mapOf("config-ref" to "C", "path" to "/a")),
                    mappingContext,
                ),
            )
        assertNull(read.targetConfig)

        val list =
            assertIs<SftpListConfig>(
                SftpListMapper.map(
                    operation("list", mapOf("config-ref" to "C", "directoryPath" to "/inbox")),
                    mappingContext,
                ),
            )
        assertNull(list.targetConfig)
    }

    @Test
    fun `emitters preserve native targetConfig including escapes and TargetConfig import`() {
        val connection = SftpConnectionRef.ByName("C")
        val withValue =
            TargetConfig(target = "download", targetValue = "#[payload]")
        val targetOnly = TargetConfig(target = "listing", targetValue = null)
        val escaped = TargetConfig(target = "result\"\$name", targetValue = "#[\"line one\\n\$payload\"]")

        val readWithValueCtx = CodegenContext("com.example")
        val readWithValueCode =
            SftpReadCodeEmitter.emit(
                SftpReadConfig(connection = connection, path = "/a", targetConfig = withValue),
                readWithValueCtx,
            )
        assertContains(readWithValueCode, "targetConfig = TargetConfig(\"download\", \"#[payload]\")")
        assertContains(readWithValueCtx.resolvedImports(), "dev.museframework.dsl.api.config.TargetConfig")

        val listTargetOnlyCtx = CodegenContext("com.example")
        val listTargetOnlyCode =
            SftpListCodeEmitter.emit(
                SftpListConfig(connection = connection, directoryPath = "/inbox", targetConfig = targetOnly),
                listTargetOnlyCtx,
            )
        assertContains(listTargetOnlyCode, "targetConfig = TargetConfig(\"listing\")")
        assertFalse(listTargetOnlyCode.contains("targetConfig = TargetConfig(\"listing\","))
        assertContains(listTargetOnlyCtx.resolvedImports(), "dev.museframework.dsl.api.config.TargetConfig")

        val noTargetCtx = CodegenContext("com.example")
        val noTargetCode =
            SftpReadCodeEmitter.emit(SftpReadConfig(connection = connection, path = "/a"), noTargetCtx)
        assertFalse(noTargetCode.contains("targetConfig"))
        assertFalse(noTargetCtx.resolvedImports().contains("dev.museframework.dsl.api.config.TargetConfig"))

        val escapedCtx = CodegenContext("com.example")
        val escapedRead =
            SftpReadCodeEmitter.emit(
                SftpReadConfig(connection = connection, path = "/a", targetConfig = escaped),
                escapedCtx,
            )
        val escapedList =
            SftpListCodeEmitter.emit(
                SftpListConfig(connection = connection, directoryPath = "/inbox", targetConfig = escaped),
                CodegenContext("com.example"),
            )
        listOf(escapedRead, escapedList).forEach { code ->
            assertContains(code, "result\\\"")
            assertContains(code, "\\\$name")
            assertContains(code, "\\\$payload")
            assertContains(code, "\\\\n")
        }
    }

    private fun operation(
        tag: String,
        attrs: Map<String, String>,
        children: List<RawElement> = emptyList(),
    ): RawElement =
        RawElement(
            namespace = "sftp",
            tag = tag,
            attrs = attrs,
            children = children,
            location = SourceLocation(1, 1),
        )

    private companion object {
        val SOURCE_FILE: Path = Path.of("test.xml")
    }
}

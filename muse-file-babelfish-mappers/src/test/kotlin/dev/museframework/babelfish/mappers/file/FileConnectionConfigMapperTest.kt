package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.file.api.model.FileWriteMode
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FileConnectionConfigMapperTest {
    @Test
    fun `maps working directory and size-check defaults from connection child`() {
        val config =
            FileConnectionConfigMapper.map(
                RawConfig(
                    id = "id",
                    namespace = "file",
                    tag = "config",
                    attrs = mapOf("name" to "File_Config", "doc:name" to "File Config"),
                    children =
                        listOf(
                            RawElement(
                                namespace = "file",
                                tag = "connection",
                                attrs =
                                    mapOf(
                                        "workingDir" to "/data",
                                        "timeBetweenSizeCheck" to "40",
                                        "timeBetweenSizeCheckUnit" to "SECONDS",
                                    ),
                                children = emptyList(),
                                location = SourceLocation(1, 1),
                            ),
                        ),
                    location = SourceLocation(1, 1),
                ),
                MappingContext(BabelfishRegistry(), Path.of("test.xml")),
            )

        assertEquals("File_Config", config.name)
        assertEquals("File Config", config.docName)
        assertEquals("/data", config.workingDirectory)
        assertEquals(40L, config.timeBetweenSizeCheck)
        assertEquals(TimeUnit.SECONDS, config.timeBetweenSizeCheckUnit)
    }
}

class FileOperationMappersTest {
    private val ctx = MappingContext(BabelfishRegistry(), Path.of("test.xml"))
    private val location = SourceLocation(2, 4)

    @Test
    fun `maps read write list and mutating operations`() {
        val read =
            FileReadMapper.map(
                RawElement(
                    namespace = "file",
                    tag = "read",
                    attrs =
                        mapOf(
                            "config-ref" to "File_Config",
                            "path" to "a.txt",
                            "lock" to "true",
                            "timeBetweenSizeCheck" to "3",
                            "timeBetweenSizeCheckUnit" to "MILLISECONDS",
                            "outputMimeType" to "text/plain",
                            "outputEncoding" to "UTF-8",
                        ),
                    children = emptyList(),
                    location = location,
                ),
                ctx,
            )
        assertEquals("a.txt", read.path)
        assertEquals(true, read.lock)
        assertEquals(3L, read.timeBetweenSizeCheck)
        assertEquals(TimeUnit.MILLISECONDS, read.timeBetweenSizeCheckUnit)
        assertEquals("text/plain", read.outputMimeType)
        assertEquals("UTF-8", read.encoding)
        assertIs<ConfigRef.ByName>(read.connectionConfig)

        val write =
            FileWriteMapper.map(
                RawElement(
                    namespace = "file",
                    tag = "write",
                    attrs =
                        mapOf(
                            "config-ref" to "File_Config",
                            "path" to "b.txt",
                            "mode" to "CREATE_NEW",
                            "createParentDirectories" to "false",
                            "lock" to "true",
                        ),
                    children =
                        listOf(
                            RawElement(
                                namespace = "file",
                                tag = "content",
                                attrs = emptyMap(),
                                children = emptyList(),
                                textContent = "#[payload]",
                                location = location,
                            ),
                        ),
                    location = location,
                ),
                ctx,
            )
        assertEquals("b.txt", write.path)
        assertEquals("#[payload]", write.content)
        assertEquals(false, write.createParentDirectories)
        assertEquals(true, write.lock)
        assertEquals(FileWriteMode.CREATE_NEW, write.mode)

        val list =
            FileListMapper.map(
                RawElement(
                    namespace = "file",
                    tag = "list",
                    attrs = mapOf("config-ref" to "File_Config", "directoryPath" to "inbox", "recursive" to "true"),
                    children = emptyList(),
                    location = location,
                ),
                ctx,
            )
        assertEquals("inbox", list.directoryPath)
        assertEquals(true, list.recursive)
        assertNull(list.matcher)
    }
}

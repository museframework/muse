package dev.museframework.file.api.dsl

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.dsl.flow
import dev.museframework.file.api.config.*
import dev.museframework.file.api.model.FileWriteMode
import dev.museframework.file.api.model.LocalSubsetList
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOperationsDslTest {
    private val connection = fileConnection { name = "files" }

    @Test
    fun `operation DSL adds every supported file operation in declaration order`() {
        val target = TargetConfig("listed")
        val configuredFlow =
            flow("file-operations") {
                fileRead(connection) {
                    path = "#[vars.path]"
                    lock = true
                    outputMimeType = "text/plain"
                    encoding = "UTF-8"
                }
                fileWrite(connection) {
                    path = "out/result.txt"
                    content = "#[payload]"
                    mode = FileWriteMode.APPEND
                    createParentDirectories = false
                }
                fileList(connection) {
                    directoryPath = "in"
                    recursive = true
                    subset = LocalSubsetList(limit = 2)
                    targetConfig = target
                }
                fileCopy(connection) {
                    sourcePath = "in/a.txt"
                    targetPath = "copy"
                    renameTo = "b.txt"
                }
                fileMove(connection) {
                    sourcePath = "copy/b.txt"
                    targetPath = "archive"
                    overwrite = true
                }
                fileDelete(connection) { path = "archive/b.txt" }
                fileRename(connection) {
                    path = "out/result.txt"
                    to = "renamed.txt"
                }
                fileCreateDirectory(connection) { directoryPath = "empty" }
            }

        assertEquals(
            listOf(
                FileReadConfig::class,
                FileWriteConfig::class,
                FileListConfig::class,
                FileCopyConfig::class,
                FileMoveConfig::class,
                FileDeleteConfig::class,
                FileRenameConfig::class,
                FileCreateDirectoryConfig::class,
            ),
            configuredFlow.pipeline.map { it::class },
        )

        val read = assertIs<FileReadConfig>(configuredFlow.pipeline[0])
        assertEquals("#[vars.path]", read.path)
        assertTrue(read.lock)
        assertEquals("text/plain", read.outputMimeType)

        val write = assertIs<FileWriteConfig>(configuredFlow.pipeline[1])
        assertEquals(FileWriteMode.APPEND, write.mode)
        assertFalse(write.createParentDirectories)

        val list = assertIs<FileListConfig>(configuredFlow.pipeline[2])
        assertTrue(list.recursive)
        assertEquals(2, list.subset?.limit)
        assertEquals(target, list.targetConfig)

        val copy = assertIs<FileCopyConfig>(configuredFlow.pipeline[3])
        assertEquals("b.txt", copy.renameTo)

        val move = assertIs<FileMoveConfig>(configuredFlow.pipeline[4])
        assertTrue(move.overwrite)
    }

    @Test
    fun `operation defaults match the Mule file connector contract`() {
        val configuredFlow =
            flow("defaults") {
                fileWrite(connection) { path = "a.txt" }
                fileList(connection) { directoryPath = "." }
                fileCopy(connection) {
                    sourcePath = "a.txt"
                    targetPath = "copy"
                }
            }

        val write = assertIs<FileWriteConfig>(configuredFlow.pipeline[0])
        assertNull(write.content)
        assertTrue(write.createParentDirectories)
        assertFalse(write.lock)
        assertEquals(FileWriteMode.OVERWRITE, write.mode)

        val list = assertIs<FileListConfig>(configuredFlow.pipeline[1])
        assertFalse(list.recursive)

        val copy = assertIs<FileCopyConfig>(configuredFlow.pipeline[2])
        assertTrue(copy.createParentDirectories)
        assertFalse(copy.overwrite)
    }

    @Test
    fun `operations accept named configuration references`() {
        val configuredFlow =
            flow("named") {
                fileRead(fileConnection("files")) { path = "a.txt" }
            }

        val read = assertIs<FileReadConfig>(configuredFlow.pipeline.single())
        assertEquals("files", assertIs<ConfigRef.ByName>(read.connectionConfig).name)
    }
}

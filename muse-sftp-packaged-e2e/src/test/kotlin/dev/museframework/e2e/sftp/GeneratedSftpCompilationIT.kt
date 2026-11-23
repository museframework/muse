package dev.museframework.e2e.sftp

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.e2e.sftp.generated.SftpGeneratedConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpCopyConfig
import dev.museframework.sftp.api.config.SftpCreateDirectoryConfig
import dev.museframework.sftp.api.config.SftpDeleteConfig
import dev.museframework.sftp.api.config.SftpListConfig
import dev.museframework.sftp.api.config.SftpMoveConfig
import dev.museframework.sftp.api.config.SftpReadConfig
import dev.museframework.sftp.api.config.SftpRenameConfig
import dev.museframework.sftp.api.config.SftpWriteConfig
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType
import dev.museframework.sftp.api.model.SftpWriteMode
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratedSftpCompilationIT {
    @Test
    fun `generated SFTP configuration and application classes load from compiled bytecode`() {
        val generatedConfig = loadGeneratedClass("SftpGeneratedConfig")
        assertTrue(
            MuseConfigDefinition::class.java.isAssignableFrom(generatedConfig),
            "${generatedConfig.name} must extend MuseConfigDefinition",
        )
        assertNotNull(generatedConfig.getDeclaredConstructor().newInstance())

        val applicationClass = loadGeneratedClass("MuseSftpPackagedE2eApplicationKt")
        val mainMethod = applicationClass.getDeclaredMethod("main", Array<String>::class.java)
        assertTrue(Modifier.isPublic(mainMethod.modifiers))
        assertTrue(Modifier.isStatic(mainMethod.modifiers))
        assertEquals(Void.TYPE, mainMethod.returnType)
    }

    @Test
    fun `compiled SFTP DSL retains remote paths matchers transfer options and dynamic expressions`() {
        val config = SftpGeneratedConfig()
        val connection = config.generatedSftp
        assertEquals("generated-sftp", connection.name)
        assertEquals("packaged-sftp-connection-original", connection.docId)
        assertEquals(18822, connection.port)
        assertEquals("/", connection.workingDirectory)
        assertEquals(5_000L, connection.operationTimeoutMs)
        assertEquals(10_000L, connection.authenticationTimeoutMs)

        val flow = config.sftpOptions
        assertEquals("sftp-options-id", flow.docId)
        val pipeline = flow.pipeline
        assertEquals(9, pipeline.size)
        val list = assertIs<SftpListConfig>(pipeline[0])
        assertEquals("packaged-sftp-list-original", list.docId)
        assertEquals(SftpConnectionRef.ByName("generated-sftp"), list.connection)
        assertEquals("/incoming\\\$batch", list.directoryPath)
        assertEquals(
            SftpFileMatcher(
                pathPattern = "regex:in\\d+",
                fileType = SftpFileType.SYMBOLIC_LINK,
                minSize = 1,
                maxSize = 17,
                caseSensitive = false,
            ),
            list.matcher,
        )
        assertEquals(TargetConfig("listing"), list.targetConfig)
        val write = assertIs<SftpWriteConfig>(pipeline[1])
        assertEquals("packaged-sftp-write-original", write.docId)
        assertEquals("#[vars.remotePath]", write.path)
        assertEquals("#[vars.body ++ \"\$suffix\"]", write.content)
        assertEquals("#[vars.mode]", write.modeExpression)
        assertEquals(SftpWriteMode.OVERWRITE, write.mode)
        assertTrue(write.createParentDirectories)
        val read = assertIs<SftpReadConfig>(pipeline[2])
        assertEquals("packaged-sftp-read-original", read.docId)
        assertEquals("/in/\"quoted\"", read.path)
        assertEquals(TargetConfig("bytes", "#[payload]"), read.targetConfig)
        val copy = assertIs<SftpCopyConfig>(pipeline[3])
        assertEquals("packaged-sftp-copy-original", copy.docId)
        assertEquals("/copy", copy.targetDirectory)
        assertEquals("copy-\$batch", copy.renameTo)
        val move = assertIs<SftpMoveConfig>(pipeline[4])
        assertEquals("packaged-sftp-move-original", move.docId)
        assertEquals("/archive", move.targetDirectory)
        assertTrue(move.overwrite)
        assertEquals(null, move.renameTo)
        val rename = assertIs<SftpRenameConfig>(pipeline[5])
        assertEquals("packaged-sftp-rename-original", rename.docId)
        assertEquals("new-\$name", rename.newName)
        val delete = assertIs<SftpDeleteConfig>(pipeline[6])
        assertEquals("packaged-sftp-delete-original", delete.docId)
        assertEquals("/archive/old", delete.path)
        val createDirectory = assertIs<SftpCreateDirectoryConfig>(pipeline[7])
        assertEquals("packaged-sftp-create-directory-original", createDirectory.docId)
        assertEquals("/next", createDirectory.directoryPath)
        val secondaryWrite = assertIs<SftpWriteConfig>(pipeline[8])
        assertEquals(
            "packaged-sftp-write-" + '"' + "secondary" + '"' + '-' + '$' + "batch",
            secondaryWrite.docId,
        )
    }

    private fun loadGeneratedClass(simpleName: String): Class<*> =
        Class.forName("$GENERATED_PACKAGE.$simpleName")

    private companion object {
        const val GENERATED_PACKAGE = "dev.museframework.e2e.sftp.generated"
    }
}

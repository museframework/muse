package dev.museframework.babelfish.mappers.sftp

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.sftp.api.config.SftpConnectionConfig
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
import dev.museframework.sftp.api.model.SftpPostAction
import dev.museframework.sftp.api.model.SftpWriteMode
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SftpTranslationIT {
    @Test
    fun `translates and emits every sftp provider component category`() {
        val provider = SftpMapperProvider()
        val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
        val result =
            MuseBabelfish.translateXmlToMuseKotlinDsl(
                Path.of("src/test/resources/mule/sftp/sftp-listener-operations.xml"),
                registry,
            )

        val connection = assertIs<SftpConnectionConfig>(result.usedConfigs.getValue("Sftp_Config"))
        assertEquals("SFTP Config", connection.docName)
        assertEquals("sftp.example.com", connection.host)
        assertEquals(2222, connection.port)
        assertEquals("muse", connection.username)
        assertEquals("secret", connection.password)
        assertEquals("/data", connection.workingDirectory)
        assertEquals("/etc/muse/known_hosts", connection.knownHostsFile)
        assertEquals(SftpHostKeyChecking.STRICT, connection.hostKeyChecking)
        assertEquals(15_000L, connection.connectionTimeoutMs)
        assertEquals(45_000L, connection.operationTimeoutMs)

        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        val listener = assertIs<SftpListenerConfig>(flow.source)
        val listenerRef = assertIs<SftpConnectionRef.ByName>(listener.connection)
        assertEquals("Sftp_Config", listenerRef.name)
        assertEquals("inbox", listener.directory)
        assertEquals(false, listener.recursive)
        assertEquals(true, listener.watermarkEnabled)
        assertEquals(2_000L, listener.pollIntervalMs)
        assertEquals(10L, listener.sizeCheckDelayMs)
        assertEquals(false, listener.applyPostActionOnFailure)
        val move = assertIs<SftpPostAction.Move>(listener.postAction)
        assertEquals("archive", move.targetDirectory)
        assertEquals(true, move.overwrite)
        assertEquals("*.csv", listener.matcher.filenamePattern)
        assertEquals(100L, listener.matcher.maxSize)
        assertEquals(SftpFileType.REGULAR_FILE, listener.matcher.fileType)

        val pipeline = flow.pipeline
        assertEquals(8, pipeline.size)

        val read = assertIs<SftpReadConfig>(pipeline[0])
        assertEquals("inbox/input.csv", read.path)

        val write = assertIs<SftpWriteConfig>(pipeline[1])
        assertEquals("outbox/output.csv", write.path)
        assertEquals(SftpWriteMode.APPEND, write.mode)
        assertEquals("#[payload]", write.content)

        val list = assertIs<SftpListConfig>(pipeline[2])
        assertEquals("inbox", list.directoryPath)
        assertEquals(true, list.recursive)
        assertEquals("*.csv", list.matcher.filenamePattern)
        assertEquals(100L, list.matcher.maxSize)

        val copy = assertIs<SftpCopyConfig>(pipeline[3])
        assertEquals("inbox/input.csv", copy.sourcePath)
        assertEquals("copies", copy.targetDirectory)
        assertEquals("copied.csv", copy.renameTo)
        assertEquals(true, copy.overwrite)

        val moveOp = assertIs<SftpMoveConfig>(pipeline[4])
        assertEquals("inbox/staging.csv", moveOp.sourcePath)
        assertEquals("moved", moveOp.targetDirectory)
        assertEquals(false, moveOp.createParentDirectories)

        val delete = assertIs<SftpDeleteConfig>(pipeline[5])
        assertEquals("inbox/obsolete.csv", delete.path)

        val rename = assertIs<SftpRenameConfig>(pipeline[6])
        assertEquals("inbox/old-name.csv", rename.path)
        assertEquals("new-name.csv", rename.newName)
        assertEquals(true, rename.overwrite)

        val createDirectory = assertIs<SftpCreateDirectoryConfig>(pipeline[7])
        assertEquals("inbox/created", createDirectory.directoryPath)

        val code =
            KotlinCodeEmitter()
                .apply { provider.registerEmitters(this) }
                .emit(result, "dev.museframework.babelfish.generated")

        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpConnection")
        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpListener")
        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpRead")
        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpWrite")
        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpList")
        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpCopy")
        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpMove")
        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpDelete")
        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpRename")
        assertContains(code, "import dev.museframework.sftp.api.dsl.sftpCreateDirectory")
        assertContains(code, "sftpConnection {")
        assertContains(code, "host = \"sftp.example.com\"")
        assertContains(code, "workingDirectory = \"/data\"")
        assertContains(code, "sftpListener(\"Sftp_Config\")")
        assertContains(code, "directory = \"inbox\"")
        assertContains(code, "pollIntervalMs = 2000")
        assertContains(code, "moveAfterProcessing(\"archive\", overwrite = true)")
        assertContains(code, "sftpRead(sftpConnection(\"Sftp_Config\"))")
        assertContains(code, "path = \"inbox/input.csv\"")
        assertContains(code, "mode = SftpWriteMode.APPEND")
        assertContains(code, "content = \"#[payload]\"")
        assertContains(code, "filenamePattern = \"*.csv\"")
        assertContains(code, "sftpList(sftpConnection(\"Sftp_Config\"))")
        assertContains(code, "sftpCopy(sftpConnection(\"Sftp_Config\"))")
        assertContains(code, "targetDirectory = \"copies\"")
        assertContains(code, "sftpMove(sftpConnection(\"Sftp_Config\"))")
        assertContains(code, "sftpDelete(sftpConnection(\"Sftp_Config\"))")
        assertContains(code, "sftpRename(sftpConnection(\"Sftp_Config\"))")
        assertContains(code, "newName = \"new-name.csv\"")
        assertContains(code, "sftpCreateDirectory(sftpConnection(\"Sftp_Config\"))")
        assertFalse(code.contains("dev.museframework.connectors.sftp"))
    }

    @Test
    fun `translates synthetic real-usage shapes for identity key list matcher write and move`() {
        val provider = SftpMapperProvider()
        val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
        val result =
            MuseBabelfish.translateXmlToMuseKotlinDsl(
                Path.of("src/test/resources/mule/sftp/sftp-real-usage-shapes.xml"),
                registry,
            )

        val connection = assertIs<SftpConnectionConfig>(result.usedConfigs.getValue("Exchange_Sftp"))
        assertEquals("Exchange SFTP", connection.docName)
        assertEquals("sftp.example.test", connection.host)
        assertEquals(22, connection.port)
        assertEquals("exchange", connection.username)
        assertEquals(null, connection.password)
        assertEquals("/keys/client.pem", connection.privateKeyPath)
        assertEquals("/exchange", connection.workingDirectory)
        assertEquals(SftpHostKeyChecking.STRICT, connection.hostKeyChecking)

        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        assertEquals(null, flow.source)
        assertEquals(3, flow.pipeline.size)

        val list = assertIs<SftpListConfig>(flow.pipeline[0])
        assertEquals("/inbox", list.directoryPath)
        assertEquals("regex:orders-\\d{4}\\.csv", list.matcher.filenamePattern)
        assertEquals(SftpFileType.REGULAR_FILE, list.matcher.fileType)

        val write = assertIs<SftpWriteConfig>(flow.pipeline[1])
        assertEquals("#[vars.projectDirectory ++ '/errors/' ++ vars.fileName ++ '.json']", write.path)
        assertEquals(false, write.createParentDirectories)
        assertEquals("#[payload]", write.content)
        assertEquals(SftpWriteMode.OVERWRITE, write.mode)

        val move = assertIs<SftpMoveConfig>(flow.pipeline[2])
        assertEquals("#[attributes.path]", move.sourcePath)
        assertEquals("/archive", move.targetDirectory)
        assertEquals(true, move.overwrite)
        assertEquals("#[attributes.name ++ '.done']", move.renameTo)

        val code =
            KotlinCodeEmitter()
                .apply { provider.registerEmitters(this) }
                .emit(result, "dev.museframework.babelfish.generated")

        assertContains(code, "privateKeyPath = \"/keys/client.pem\"")
        assertContains(code, "workingDirectory = \"/exchange\"")
        assertFalse(code.contains("hostKeyChecking = SftpHostKeyChecking.INSECURE"))
        assertContains(code, "filenamePattern = \"regex:orders-\\\\d{4}\\\\.csv\"")
        assertContains(code, "fileType = SftpFileType.REGULAR_FILE")
        assertContains(code, "createParentDirectories = false")
        assertContains(
            code,
            "path = \"#[vars.projectDirectory ++ '/errors/' ++ vars.fileName ++ '.json']\"",
        )
        assertContains(code, "overwrite = true")
        assertContains(code, "renameTo = \"#[attributes.name ++ '.done']\"")
        assertFalse(code.contains("password ="))
        assertFalse(code.contains("dev.museframework.connectors.sftp"))
    }
}

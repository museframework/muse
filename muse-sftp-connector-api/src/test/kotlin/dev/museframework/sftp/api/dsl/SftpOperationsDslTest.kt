package dev.museframework.sftp.api.dsl

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.sftp.api.config.*
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType
import dev.museframework.sftp.api.model.SftpPostAction
import dev.museframework.sftp.api.model.SftpWriteMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class SftpOperationsDslTest {
    @Test
    fun `connection DSL preserves property-based port alongside static port`() {
        val config = sftpConnection {
            host = "\${sftp.host}"
            username = "\${sftp.user}"
            password = "\${sftp.password}"
            port = 2222
            portExpression = "\${sftp.port}"
        }
        assertEquals(2222, config.port)
        assertEquals("\${sftp.port}", config.portExpression)
    }

    private val connection =
        SftpConnectionConfig(
            name = "warehouse",
            host = "localhost",
            username = "muse",
            password = "secret",
            hostKeyChecking = SftpHostKeyChecking.INSECURE,
        )

    @Test
    fun `operation DSL creates every SFTP processor with typed configuration`() {
        val target = TargetConfig.of("remoteFile", "#[payload]")
        val testFlow =
            flow("sftp-operations") {
                sftpList(connection) {
                    directoryPath = "/inbound"
                    recursive = true
                    matcher = SftpFileMatcher(filenamePattern = "*.csv", minSize = 1)
                    targetConfig = target
                }
                sftpRead(connection) {
                    path = "/inbound/orders.csv"
                    targetConfig = target
                }
                sftpWrite(connection) {
                    path = "/outbound/orders.csv"
                    content = "#[payload]"
                    createParentDirectories = false
                    mode = SftpWriteMode.APPEND
                    modeExpression = "#[vars.sftpWriteMode]"
                }
                sftpCopy(connection) {
                    sourcePath = "/outbound/orders.csv"
                    targetDirectory = "/archive"
                    renameTo = "orders-copy.csv"
                    overwrite = true
                }
                sftpMove(connection) {
                    sourcePath = "/archive/orders-copy.csv"
                    targetDirectory = "/processed"
                    renameTo = "orders-final.csv"
                    createParentDirectories = false
                }
                sftpRename(connection) {
                    path = "/processed/orders-final.csv"
                    newName = "orders-renamed.csv"
                    overwrite = true
                }
                sftpDelete(connection) {
                    path = "/processed/orders-renamed.csv"
                }
                sftpCreateDirectory(connection) {
                    directoryPath = "/ready/nested"
                }
            }

        assertEquals(8, testFlow.pipeline.size)
        val list = assertIs<SftpListConfig>(testFlow.pipeline[0])
        assertEquals("/inbound", list.directoryPath)
        assertEquals(true, list.recursive)
        assertEquals("*.csv", list.matcher.filenamePattern)
        assertEquals(target, list.targetConfig)
        assertEquals(SftpConnectionRef.ByReference(connection), list.connection)

        val read = assertIs<SftpReadConfig>(testFlow.pipeline[1])
        assertEquals("/inbound/orders.csv", read.path)
        assertEquals(target, read.targetConfig)

        val write = assertIs<SftpWriteConfig>(testFlow.pipeline[2])
        assertEquals("#[payload]", write.content)
        assertEquals(false, write.createParentDirectories)
        assertEquals(SftpWriteMode.APPEND, write.mode)
        assertEquals("#[vars.sftpWriteMode]", write.modeExpression)

        val copy = assertIs<SftpCopyConfig>(testFlow.pipeline[3])
        assertEquals("orders-copy.csv", copy.renameTo)
        assertEquals(true, copy.overwrite)

        val move = assertIs<SftpMoveConfig>(testFlow.pipeline[4])
        assertEquals("/processed", move.targetDirectory)
        assertEquals(false, move.createParentDirectories)

        val rename = assertIs<SftpRenameConfig>(testFlow.pipeline[5])
        assertEquals("orders-renamed.csv", rename.newName)
        assertEquals(true, rename.overwrite)

        assertEquals("/processed/orders-renamed.csv", assertIs<SftpDeleteConfig>(testFlow.pipeline[6]).path)
        assertEquals("/ready/nested", assertIs<SftpCreateDirectoryConfig>(testFlow.pipeline[7]).directoryPath)
        assertNotEquals(testFlow.pipeline[0].docId, testFlow.pipeline[1].docId)
    }

    @Test
    fun `operation DSL accepts named typed connection reference`() {
        val reference = sftpConnection("warehouse")
        val testFlow = flow("named-sftp") { sftpList(reference) }

        assertEquals(reference, assertIs<SftpListConfig>(testFlow.pipeline.single()).connection)
    }

    @Test
    fun `listener DSL creates polling source with post processing`() {
        val testFlow =
            flow("sftp-listener") {
                sftpListener(connection) {
                    directory = "/inbound"
                    outputMimeType = "application/csv; separator=|; streaming=true; header=false"
                    nonRepeatable = true
                    recursive = false
                    pollIntervalMs = 250
                    sizeCheckDelayMs = 50
                    watermarkEnabled = false
                    matcher = SftpFileMatcher(fileType = SftpFileType.REGULAR_FILE)
                    moveAfterProcessing("/processed", overwrite = true)
                    applyPostActionOnFailure = true
                }
            }

        val listener = assertIs<SftpListenerConfig>(testFlow.source)
        assertEquals("/inbound", listener.directory)
        assertEquals("application/csv; separator=|; streaming=true; header=false", listener.outputMimeType)
        assertEquals(true, listener.nonRepeatable)
        assertEquals(false, listener.recursive)
        assertEquals(250, listener.pollIntervalMs)
        assertEquals(50, listener.sizeCheckDelayMs)
        assertEquals(false, listener.watermarkEnabled)
        assertEquals(SftpPostAction.Move("/processed", overwrite = true), listener.postAction)
        assertEquals(true, listener.applyPostActionOnFailure)
    }

    @Test
    fun `operation and matcher validation rejects ambiguous paths and ranges`() {
        val reference = SftpConnectionRef.ByReference(connection)

        assertFailsWith<IllegalArgumentException> { SftpReadConfig(connection = reference, path = "") }
        assertFailsWith<IllegalArgumentException> {
            SftpRenameConfig(connection = reference, path = "/file", newName = "nested/file")
        }
        assertFailsWith<IllegalArgumentException> {
            SftpCopyConfig(
                connection = reference,
                sourcePath = "/file",
                targetDirectory = "/target",
                renameTo = "..",
            )
        }
        assertFailsWith<IllegalArgumentException> { SftpFileMatcher(minSize = 20, maxSize = 10) }
        assertFailsWith<IllegalArgumentException> {
            SftpFileMatcher(
                modifiedSince = Instant.parse("2026-01-02T00:00:00Z"),
                modifiedUntil = Instant.parse("2026-01-01T00:00:00Z"),
            )
        }
        assertFailsWith<IllegalArgumentException> { SftpListenerConfig(connection = reference, pollIntervalMs = 0) }
    }
}

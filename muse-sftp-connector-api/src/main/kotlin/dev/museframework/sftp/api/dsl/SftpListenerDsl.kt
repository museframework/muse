package dev.museframework.sftp.api.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.builder.FlowBuilder
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpListenerConfig
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType
import dev.museframework.sftp.api.model.SftpPostAction
import java.util.UUID

@MuseDsl
class SftpListenerBuilder internal constructor(
    private val connection: SftpConnectionRef,
) {
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var directory: String = "/"
    var recursive: Boolean = true
    var matcher: SftpFileMatcher = SftpFileMatcher(fileType = SftpFileType.REGULAR_FILE)
    var pollIntervalMs: Long = 1_000
    var sizeCheckDelayMs: Long? = null
    var watermarkEnabled: Boolean = true
    var postAction: SftpPostAction = SftpPostAction.None
    var applyPostActionOnFailure: Boolean = false
    var outputMimeType: String? = null
    var nonRepeatable: Boolean = false

    fun deleteAfterProcessing() {
        postAction = SftpPostAction.Delete
    }

    fun moveAfterProcessing(
        targetDirectory: String,
        overwrite: Boolean = false,
    ) {
        postAction = SftpPostAction.Move(targetDirectory, overwrite)
    }

    fun build(): SftpListenerConfig {
        val callSite = CallSiteCapture.capture()
        return SftpListenerConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            connection = connection,
            directory = directory,
            recursive = recursive,
            matcher = matcher,
            pollIntervalMs = pollIntervalMs,
            sizeCheckDelayMs = sizeCheckDelayMs,
            watermarkEnabled = watermarkEnabled,
            postAction = postAction,
            applyPostActionOnFailure = applyPostActionOnFailure,
            outputMimeType = outputMimeType,
            nonRepeatable = nonRepeatable,
        )
    }
}

fun FlowBuilder.sftpListener(
    connection: SftpConnectionRef,
    block: SftpListenerBuilder.() -> Unit = {},
) {
    source(SftpListenerBuilder(connection).apply(block).build())
}

fun FlowBuilder.sftpListener(
    connection: SftpConnectionConfig,
    block: SftpListenerBuilder.() -> Unit = {},
) = sftpListener(SftpConnectionRef.ByReference(connection), block)

fun FlowBuilder.sftpListener(
    connectionName: String,
    block: SftpListenerBuilder.() -> Unit = {},
) = sftpListener(SftpConnectionRef.ByName(connectionName), block)

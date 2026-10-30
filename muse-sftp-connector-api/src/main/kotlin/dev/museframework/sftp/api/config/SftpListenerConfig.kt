package dev.museframework.sftp.api.config

import dev.museframework.dsl.api.model.component.Source
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType
import dev.museframework.sftp.api.model.SftpPostAction
import java.util.UUID

data class SftpListenerConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connection: SftpConnectionRef,
    val directory: String = "/",
    val recursive: Boolean = true,
    val matcher: SftpFileMatcher = SftpFileMatcher(fileType = SftpFileType.REGULAR_FILE),
    val pollIntervalMs: Long = 1_000,
    val sizeCheckDelayMs: Long? = null,
    val watermarkEnabled: Boolean = true,
    val postAction: SftpPostAction = SftpPostAction.None,
    val applyPostActionOnFailure: Boolean = false,
    val outputMimeType: String? = null,
    val nonRepeatable: Boolean = false,
) : Source {
    init {
        require(directory.isNotBlank()) { "SFTP listener directory must not be blank" }
        require(pollIntervalMs > 0) { "SFTP listener pollIntervalMs must be greater than zero" }
        require(sizeCheckDelayMs == null || sizeCheckDelayMs > 0) {
            "SFTP listener sizeCheckDelayMs must be greater than zero"
        }
    }
}

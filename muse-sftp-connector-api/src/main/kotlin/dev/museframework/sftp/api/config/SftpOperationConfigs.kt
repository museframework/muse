package dev.museframework.sftp.api.config

import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpTransferRequest
import dev.museframework.sftp.api.model.SftpWriteMode
import java.util.UUID

sealed interface SftpOperationConfig : Processor {
    val connection: SftpConnectionRef
}

data class SftpListConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    override val connection: SftpConnectionRef,
    val directoryPath: String = "/",
    val recursive: Boolean = false,
    val matcher: SftpFileMatcher = SftpFileMatcher(),
    val targetConfig: TargetConfig? = null,
) : SftpOperationConfig {
    init {
        requireRemotePath(directoryPath, "directoryPath")
    }
}

data class SftpReadConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    override val connection: SftpConnectionRef,
    val path: String,
    val targetConfig: TargetConfig? = null,
) : SftpOperationConfig {
    init {
        requireRemotePath(path, "path")
    }
}

data class SftpWriteConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    override val connection: SftpConnectionRef,
    val path: String,
    val content: String? = null,
    val createParentDirectories: Boolean = true,
    val mode: SftpWriteMode = SftpWriteMode.OVERWRITE,
    /** Evaluated per event; overrides mode. Invalid results never fall back to the static mode. */
    val modeExpression: String? = null,
) : SftpOperationConfig {
    init {
        requireRemotePath(path, "path")
    }
}

data class SftpCopyConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    override val connection: SftpConnectionRef,
    val sourcePath: String,
    val targetDirectory: String,
    val createParentDirectories: Boolean = true,
    val overwrite: Boolean = false,
    val renameTo: String? = null,
) : SftpOperationConfig {
    val transfer: SftpTransferRequest =
        SftpTransferRequest(
            sourcePath = sourcePath,
            targetDirectory = targetDirectory,
            createParentDirectories = createParentDirectories,
            overwrite = overwrite,
            renameTo = renameTo,
        )
}

data class SftpMoveConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    override val connection: SftpConnectionRef,
    val sourcePath: String,
    val targetDirectory: String,
    val createParentDirectories: Boolean = true,
    val overwrite: Boolean = false,
    val renameTo: String? = null,
) : SftpOperationConfig {
    val transfer: SftpTransferRequest =
        SftpTransferRequest(
            sourcePath = sourcePath,
            targetDirectory = targetDirectory,
            createParentDirectories = createParentDirectories,
            overwrite = overwrite,
            renameTo = renameTo,
        )
}

data class SftpDeleteConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    override val connection: SftpConnectionRef,
    val path: String,
) : SftpOperationConfig {
    init {
        requireRemotePath(path, "path")
    }
}

data class SftpRenameConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    override val connection: SftpConnectionRef,
    val path: String,
    val newName: String,
    val overwrite: Boolean = false,
) : SftpOperationConfig {
    init {
        requireRemotePath(path, "path")
        requireRemoteName(newName, "newName")
    }
}

data class SftpCreateDirectoryConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    override val connection: SftpConnectionRef,
    val directoryPath: String,
) : SftpOperationConfig {
    init {
        requireRemotePath(directoryPath, "directoryPath")
    }
}

private fun requireRemotePath(value: String, name: String) {
    require(value.isNotBlank()) { "SFTP $name must not be blank" }
    require('\u0000' !in value) { "SFTP $name must not contain a NUL character" }
}

private fun requireRemoteName(value: String?, name: String) {
    if (value == null) return
    require(value.isNotBlank()) { "SFTP $name must not be blank" }
    require('/' !in value && '\\' !in value) { "SFTP $name must be a file name, not a path" }
    require(value != "." && value != "..") { "SFTP $name must not be '.' or '..'" }
}

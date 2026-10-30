package dev.museframework.sftp.api.model

import java.time.Instant

data class SftpFileAttributes(
    val path: String,
    val name: String,
    val size: Long,
    val modifiedAt: Instant?,
    val regularFile: Boolean,
    val directory: Boolean,
    val symbolicLink: Boolean,
    val permissions: Int,
)

enum class SftpWriteMode {
    OVERWRITE,
    APPEND,
    CREATE_NEW,
}

enum class SftpFileType {
    ANY,
    REGULAR_FILE,
    DIRECTORY,
    SYMBOLIC_LINK,
}

data class SftpFileMatcher(
    val filenamePattern: String? = null,
    val pathPattern: String? = null,
    val fileType: SftpFileType = SftpFileType.ANY,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedSince: Instant? = null,
    val modifiedUntil: Instant? = null,
    val caseSensitive: Boolean = true,
) {
    init {
        require(filenamePattern == null || filenamePattern.isNotBlank()) {
            "SFTP filenamePattern must not be blank"
        }
        require(pathPattern == null || pathPattern.isNotBlank()) {
            "SFTP pathPattern must not be blank"
        }
        require(minSize == null || minSize >= 0) { "SFTP minSize must not be negative" }
        require(maxSize == null || maxSize >= 0) { "SFTP maxSize must not be negative" }
        require(minSize == null || maxSize == null || minSize <= maxSize) {
            "SFTP minSize must not exceed maxSize"
        }
        require(modifiedSince == null || modifiedUntil == null || !modifiedSince.isAfter(modifiedUntil)) {
            "SFTP modifiedSince must not be after modifiedUntil"
        }
    }
}

sealed interface SftpPostAction {
    data object None : SftpPostAction

    data object Delete : SftpPostAction

    data class Move(
        val targetDirectory: String,
        val overwrite: Boolean = false,
    ) : SftpPostAction {
        init {
            require(targetDirectory.isNotBlank()) { "SFTP post-action targetDirectory must not be blank" }
        }
    }
}

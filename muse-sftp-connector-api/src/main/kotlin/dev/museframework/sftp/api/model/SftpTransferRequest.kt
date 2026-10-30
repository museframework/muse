package dev.museframework.sftp.api.model

/**
 * Shared copy/move transfer arguments. Kept as one type so the five fields do not travel as a bare clump.
 */
data class SftpTransferRequest(
    val sourcePath: String,
    val targetDirectory: String,
    val createParentDirectories: Boolean = true,
    val overwrite: Boolean = false,
    val renameTo: String? = null,
) {
    init {
        require(sourcePath.isNotBlank()) { "SFTP sourcePath must not be blank" }
        require('\u0000' !in sourcePath) { "SFTP sourcePath must not contain a NUL character" }
        require(targetDirectory.isNotBlank()) { "SFTP targetDirectory must not be blank" }
        require('\u0000' !in targetDirectory) { "SFTP targetDirectory must not contain a NUL character" }
        if (renameTo != null) {
            require(renameTo.isNotBlank()) { "SFTP renameTo must not be blank" }
            require('/' !in renameTo && '\\' !in renameTo) {
                "SFTP renameTo must be a file name, not a path"
            }
            require(renameTo != "." && renameTo != "..") { "SFTP renameTo must not be '.' or '..'" }
        }
    }
}

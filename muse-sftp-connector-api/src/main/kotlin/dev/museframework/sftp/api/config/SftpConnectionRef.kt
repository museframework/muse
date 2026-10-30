package dev.museframework.sftp.api.config

sealed interface SftpConnectionRef {
    data class ByReference(
        val config: SftpConnectionConfig,
    ) : SftpConnectionRef

    data class ByName(
        val name: String,
    ) : SftpConnectionRef {
        init {
            require(name.isNotBlank()) { "SFTP connection reference name must not be blank" }
        }
    }
}

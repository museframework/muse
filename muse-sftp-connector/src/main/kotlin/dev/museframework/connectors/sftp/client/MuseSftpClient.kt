package dev.museframework.connectors.sftp.client

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.sftp.api.model.SftpFileAttributes
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpTransferRequest
import dev.museframework.sftp.api.model.SftpWriteMode
import java.io.InputStream

internal interface MuseSftpClient {
    fun stat(path: String): SftpFileAttributes

    fun list(
        directoryPath: String,
        recursive: Boolean,
        matcher: SftpFileMatcher,
    ): List<SftpFileAttributes>

    fun openRead(path: String): SftpReadHandle

    fun write(
        path: String,
        content: InputStream,
        mode: SftpWriteMode,
        createParentDirectories: Boolean,
    )

    fun copy(transfer: SftpTransferRequest)

    fun move(transfer: SftpTransferRequest)

    fun delete(path: String)

    fun rename(
        path: String,
        newName: String,
        overwrite: Boolean,
    )

    fun createDirectory(path: String)
}

internal fun interface MuseSftpClientFactory {
    fun create(config: dev.museframework.sftp.api.config.SftpConnectionConfig): MuseSftpClient
}

internal class SftpReadHandle(
    val inputStream: InputStream,
    val attributes: SftpFileAttributes,
    private val closeResources: () -> Unit,
) : AutoCloseable {
    override fun close() = closeResources()
}

internal class SftpClientFailure(
    val errorType: ErrorType,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

package dev.museframework.sftp.api.model

import dev.museframework.dsl.api.model.error.ErrorType

object SftpErrorType {
    val CONNECTIVITY = ErrorType("SFTP", "CONNECTIVITY", ErrorType.Mule.CONNECTIVITY)
    val CONNECTION_TIMEOUT = ErrorType("SFTP", "CONNECTION_TIMEOUT", ErrorType.Mule.TIMEOUT)
    val INVALID_CREDENTIALS = ErrorType("SFTP", "INVALID_CREDENTIALS", ErrorType.Mule.CLIENT_SECURITY)
    val HOST_KEY_VERIFICATION = ErrorType("SFTP", "HOST_KEY_VERIFICATION", ErrorType.Mule.CLIENT_SECURITY)
    val FILE_NOT_FOUND = ErrorType("SFTP", "FILE_NOT_FOUND", ErrorType.Mule.ANY)
    val FILE_ALREADY_EXISTS = ErrorType("SFTP", "FILE_ALREADY_EXISTS", ErrorType.Mule.ANY)
    val ACCESS_DENIED = ErrorType("SFTP", "ACCESS_DENIED", ErrorType.Mule.NOT_PERMITTED)
    val ILLEGAL_PATH = ErrorType("SFTP", "ILLEGAL_PATH", ErrorType.Mule.VALIDATION)
    val ILLEGAL_CONTENT = ErrorType("SFTP", "ILLEGAL_CONTENT", ErrorType.Mule.VALIDATION)
    val NOT_A_DIRECTORY = ErrorType("SFTP", "NOT_A_DIRECTORY", ILLEGAL_PATH)
    val IS_A_DIRECTORY = ErrorType("SFTP", "IS_A_DIRECTORY", ILLEGAL_PATH)
    val OPERATION_FAILED = ErrorType("SFTP", "OPERATION_FAILED", ErrorType.Mule.UNKNOWN)
}

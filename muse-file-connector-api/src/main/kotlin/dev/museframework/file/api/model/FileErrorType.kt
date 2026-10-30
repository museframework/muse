package dev.museframework.file.api.model

import dev.museframework.dsl.api.model.error.ErrorType

object FileErrorType {
    val ANY = ErrorType("FILE", "ANY", ErrorType.Mule.ANY)
    val CONNECTIVITY = ErrorType("FILE", "CONNECTIVITY", ErrorType.Mule.CONNECTIVITY)
    val RETRY_EXHAUSTED = ErrorType("FILE", "RETRY_EXHAUSTED", CONNECTIVITY)
    val ILLEGAL_PATH = ErrorType("FILE", "ILLEGAL_PATH", ANY)
    val FILE_ALREADY_EXISTS = ErrorType("FILE", "FILE_ALREADY_EXISTS", ANY)
    val FILE_DOESNT_EXIST = ErrorType("FILE", "FILE_DOESNT_EXIST", CONNECTIVITY)
    val FILE_IS_NOT_DIRECTORY = ErrorType("FILE", "FILE_IS_NOT_DIRECTORY", CONNECTIVITY)
    val FILE_LOCK = ErrorType("FILE", "FILE_LOCK", ANY)
    val ACCESS_DENIED = ErrorType("FILE", "ACCESS_DENIED", ANY)
    val ILLEGAL_CONTENT = ErrorType("FILE", "ILLEGAL_CONTENT", ANY)
}

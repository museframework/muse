package dev.museframework.db.api.model

import dev.museframework.dsl.api.model.error.ErrorType

object DbErrorType {
    const val NAMESPACE: String = "DB"

    val ANY = ErrorType(NAMESPACE, "ANY", ErrorType.Mule.ANY)
    val CONNECTIVITY = ErrorType(NAMESPACE, "CONNECTIVITY", ANY)
    val QUERY_EXECUTION = ErrorType(NAMESPACE, "QUERY_EXECUTION", ANY)
    val BAD_SQL_SYNTAX = ErrorType(NAMESPACE, "BAD_SQL_SYNTAX", QUERY_EXECUTION)
    val INVALID_PARAMETER = ErrorType(NAMESPACE, "INVALID_PARAMETER", QUERY_EXECUTION)
    val CANNOT_LOAD_DRIVER = ErrorType(NAMESPACE, "CANNOT_LOAD_DRIVER", CONNECTIVITY)
    val SCRIPT_NOT_FOUND = ErrorType(NAMESPACE, "SCRIPT_NOT_FOUND", QUERY_EXECUTION)
}

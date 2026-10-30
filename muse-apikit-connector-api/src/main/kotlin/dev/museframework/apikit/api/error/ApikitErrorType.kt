package dev.museframework.apikit.api.error

import dev.museframework.dsl.api.model.error.ErrorType

object ApikitErrorType {
    const val NAMESPACE = "APIKIT"

    // Base APIKIT error
    val ANY = ErrorType(NAMESPACE, "ANY")

    // HTTP Status Code Mapping
    val BAD_REQUEST = ErrorType(NAMESPACE, "BAD_REQUEST", ANY) // 400
    val NOT_FOUND = ErrorType(NAMESPACE, "NOT_FOUND", BAD_REQUEST) // 404
    val METHOD_NOT_ALLOWED = ErrorType(NAMESPACE, "METHOD_NOT_ALLOWED", BAD_REQUEST) // 405
    val UNSUPPORTED_MEDIA_TYPE = ErrorType(NAMESPACE, "UNSUPPORTED_MEDIA_TYPE", BAD_REQUEST) // 415

    // Validation errors
    val VALIDATION = ErrorType(NAMESPACE, "VALIDATION", BAD_REQUEST)
    val MISSING_HEADER = ErrorType(NAMESPACE, "MISSING_HEADER", VALIDATION)
    val MISSING_QUERY_PARAM = ErrorType(NAMESPACE, "MISSING_QUERY_PARAM", VALIDATION)
    val INVALID_HEADER = ErrorType(NAMESPACE, "INVALID_HEADER", VALIDATION)
    val INVALID_QUERY_PARAM = ErrorType(NAMESPACE, "INVALID_QUERY_PARAM", VALIDATION)

    // Routing errors
    val ROUTING = ErrorType(NAMESPACE, "ROUTING", BAD_REQUEST)
    val FLOW_NOT_FOUND = ErrorType(NAMESPACE, "FLOW_NOT_FOUND", ROUTING)
}

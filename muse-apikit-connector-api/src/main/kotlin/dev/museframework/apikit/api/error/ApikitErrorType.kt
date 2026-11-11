package dev.museframework.apikit.api.error

import dev.museframework.dsl.api.model.error.ErrorType

object ApikitErrorType {
    const val NAMESPACE = "APIKIT"

    // MuleSoft APIKit REST error contract: public errors are siblings beneath APIKIT:ANY.
    val ANY = ErrorType(NAMESPACE, "ANY", ErrorType.Mule.ANY)

    val BAD_REQUEST = ErrorType(NAMESPACE, "BAD_REQUEST", ANY) // 400
    val NOT_FOUND = ErrorType(NAMESPACE, "NOT_FOUND", ANY) // 404
    val METHOD_NOT_ALLOWED = ErrorType(NAMESPACE, "METHOD_NOT_ALLOWED", ANY) // 405
    val NOT_ACCEPTABLE = ErrorType(NAMESPACE, "NOT_ACCEPTABLE", ANY) // 406; Accept validation not yet implemented
    val UNSUPPORTED_MEDIA_TYPE = ErrorType(NAMESPACE, "UNSUPPORTED_MEDIA_TYPE", ANY) // 415
    val NOT_IMPLEMENTED = ErrorType(NAMESPACE, "NOT_IMPLEMENTED", ANY) // 501

    // Muse-specific legacy identifiers retained for source compatibility. The APIKit router now
    // emits BAD_REQUEST for missing required input and NOT_IMPLEMENTED for an absent flow.
    val VALIDATION = ErrorType(NAMESPACE, "VALIDATION", BAD_REQUEST)
    val MISSING_HEADER = ErrorType(NAMESPACE, "MISSING_HEADER", VALIDATION)
    val MISSING_QUERY_PARAM = ErrorType(NAMESPACE, "MISSING_QUERY_PARAM", VALIDATION)
    val INVALID_HEADER = ErrorType(NAMESPACE, "INVALID_HEADER", VALIDATION)
    val INVALID_QUERY_PARAM = ErrorType(NAMESPACE, "INVALID_QUERY_PARAM", VALIDATION)

    val ROUTING = ErrorType(NAMESPACE, "ROUTING", BAD_REQUEST)
    val FLOW_NOT_FOUND = ErrorType(NAMESPACE, "FLOW_NOT_FOUND", ROUTING)
}

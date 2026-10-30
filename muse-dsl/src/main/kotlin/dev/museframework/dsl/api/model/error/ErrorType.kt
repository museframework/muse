package dev.museframework.dsl.api.model.error

data class ErrorType(
    val namespace: String,
    val identifier: String,
    val parent: ErrorType? = null,
) {
    override fun toString(): String = "$namespace:$identifier"

    object Mule {
        val ANY = ErrorType("MULE", "ANY")
        val TRANSFORMATION = ErrorType("MULE", "TRANSFORMATION", ANY)
        val EXPRESSION = ErrorType("MULE", "EXPRESSION", ANY)
        val VALIDATION = ErrorType("MULE", "VALIDATION", ANY)
        val DUPLICATE_MESSAGE = ErrorType("MULE", "DUPLICATE_MESSAGE", VALIDATION)
        val REDELIVERY_EXHAUSTED = ErrorType("MULE", "REDELIVERY_EXHAUSTED", ANY)
        val CONNECTIVITY = ErrorType("MULE", "CONNECTIVITY", ANY)
        val RETRY_EXHAUSTED = ErrorType("MULE", "RETRY_EXHAUSTED", CONNECTIVITY)
        val ROUTING = ErrorType("MULE", "ROUTING", ANY)
        val COMPOSITE_ROUTING = ErrorType("MULE", "COMPOSITE_ROUTING", ROUTING)
        val SECURITY = ErrorType("MULE", "SECURITY", ANY)
        val CLIENT_SECURITY = ErrorType("MULE", "CLIENT_SECURITY", SECURITY)
        val SERVER_SECURITY = ErrorType("MULE", "SERVER_SECURITY", SECURITY)
        val NOT_PERMITTED = ErrorType("MULE", "NOT_PERMITTED", SERVER_SECURITY)
        val STREAM_MAXIMUM_SIZE_EXCEEDED = ErrorType("MULE", "STREAM_MAXIMUM_SIZE_EXCEEDED", ANY)
        val TIMEOUT = ErrorType("MULE", "TIMEOUT", ANY)
        val UNKNOWN = ErrorType("MULE", "UNKNOWN", ANY)

        val ASYNC_REJECTED = ErrorType("MULE", "ASYNC_REJECTED", ANY)
        val ASYNC_INPUT_NOT_RETAINABLE = ErrorType("MULE", "ASYNC_INPUT_NOT_RETAINABLE", ANY)

        val CRITICAL = ErrorType("MULE", "CRITICAL")
        val OVERLOAD = ErrorType("MULE", "OVERLOAD", CRITICAL)
        val FLOW_BACK_PRESSURE = ErrorType("MULE", "FLOW_BACK_PRESSURE", OVERLOAD)
        val FATAL_JVM_ERROR = ErrorType("MULE", "FATAL_JVM_ERROR", CRITICAL)

        val SOURCE = ErrorType("MULE", "SOURCE")
        val SOURCE_ERROR_RESPONSE_GENERATE = ErrorType("MULE", "SOURCE_ERROR_RESPONSE_GENERATE", SOURCE)
        val SOURCE_ERROR_RESPONSE_SEND = ErrorType("MULE", "SOURCE_ERROR_RESPONSE_SEND", SOURCE)

        val SOURCE_RESPONSE = ErrorType("MULE", "SOURCE_RESPONSE")
        val SOURCE_RESPONSE_GENERATE = ErrorType("MULE", "SOURCE_RESPONSE_GENERATE", SOURCE_RESPONSE)
        val SOURCE_RESPONSE_SEND = ErrorType("MULE", "SOURCE_RESPONSE_SEND", SOURCE_RESPONSE)
    }
}

fun ErrorType.isDescendantOf(other: ErrorType): Boolean {
    var current: ErrorType? = this
    while (current != null) {
        if (current.namespace == other.namespace && current.identifier == other.identifier) return true
        current = current.parent
    }
    return false
}

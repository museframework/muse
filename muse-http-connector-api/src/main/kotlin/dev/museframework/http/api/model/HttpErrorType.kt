package dev.museframework.http.api.model

import dev.museframework.dsl.api.model.error.ErrorType

object HttpErrorType {
    const val NAMESPACE = "HTTP"

    val ANY = ErrorType(NAMESPACE, "ANY", ErrorType.Mule.ANY)

    val BAD_REQUEST = ErrorType(NAMESPACE, "BAD_REQUEST", ANY)
    val UNAUTHORIZED = ErrorType(NAMESPACE, "UNAUTHORIZED", ANY)
    val FORBIDDEN = ErrorType(NAMESPACE, "FORBIDDEN", ANY)
    val NOT_FOUND = ErrorType(NAMESPACE, "NOT_FOUND", ANY)
    val METHOD_NOT_ALLOWED = ErrorType(NAMESPACE, "METHOD_NOT_ALLOWED", ANY)
    val NOT_ACCEPTABLE = ErrorType(NAMESPACE, "NOT_ACCEPTABLE", ANY)
    val REQUEST_TIMEOUT = ErrorType(NAMESPACE, "REQUEST_TIMEOUT", ANY)
    val CONFLICT = ErrorType(NAMESPACE, "CONFLICT", ANY)
    val GONE = ErrorType(NAMESPACE, "GONE", ANY)
    val UNSUPPORTED_MEDIA = ErrorType(NAMESPACE, "UNSUPPORTED_MEDIA", ANY)
    val TOO_MANY_REQUESTS = ErrorType(NAMESPACE, "TOO_MANY_REQUESTS", ANY)
    val CLIENT_SECURITY = ErrorType(NAMESPACE, "CLIENT_SECURITY", ANY)
    val CLIENT_ERROR = ErrorType(NAMESPACE, "CLIENT_ERROR", ANY)

    val INTERNAL_SERVER_ERROR = ErrorType(NAMESPACE, "INTERNAL_SERVER_ERROR", ANY)
    val SERVICE_UNAVAILABLE = ErrorType(NAMESPACE, "SERVICE_UNAVAILABLE", ANY)
    val GATEWAY_TIMEOUT = ErrorType(NAMESPACE, "GATEWAY_TIMEOUT", ANY)
    val SERVER_ERROR = ErrorType(NAMESPACE, "SERVER_ERROR", ANY)

    val CONNECTIVITY = ErrorType(NAMESPACE, "CONNECTIVITY", ANY)
    val TIMEOUT = ErrorType(NAMESPACE, "TIMEOUT", ANY)
    val SECURITY = ErrorType(NAMESPACE, "SECURITY", ANY)

    fun fromStatusCode(statusCode: Int): ErrorType =
        when (statusCode) {
            400 -> BAD_REQUEST
            401 -> UNAUTHORIZED
            403 -> FORBIDDEN
            404 -> NOT_FOUND
            405 -> METHOD_NOT_ALLOWED
            406 -> NOT_ACCEPTABLE
            408 -> REQUEST_TIMEOUT
            409 -> CONFLICT
            410 -> GONE
            415 -> UNSUPPORTED_MEDIA
            429 -> TOO_MANY_REQUESTS
            500 -> INTERNAL_SERVER_ERROR
            503 -> SERVICE_UNAVAILABLE
            504 -> GATEWAY_TIMEOUT
            in 400..499 -> CLIENT_ERROR
            in 500..599 -> SERVER_ERROR
            else -> CLIENT_ERROR
        }
}

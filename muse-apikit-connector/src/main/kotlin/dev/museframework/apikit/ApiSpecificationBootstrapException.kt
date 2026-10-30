package dev.museframework.apikit

sealed class ApiSpecificationBootstrapException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ApiSpecificationNotConfiguredException(
    configName: String,
) : ApiSpecificationBootstrapException(
        "APIKit configuration '$configName' must declare a specification path",
    )

class ApiSpecificationNotFoundException(
    val specPath: String,
) : ApiSpecificationBootstrapException(
        "API specification not found on the classpath: $specPath",
    )

class ApiSpecificationReadException(
    val specPath: String,
    cause: Throwable,
) : ApiSpecificationBootstrapException(
        "Unable to read API specification '$specPath': ${cause.message ?: cause.javaClass.simpleName}",
        cause,
    )

class ApiSpecificationParseException(
    val specPath: String,
    detail: String,
    cause: Throwable? = null,
) : ApiSpecificationBootstrapException(
        "Invalid RAML specification '$specPath': $detail",
        cause,
    )

class ApiSpecificationEmptyException(
    val specPath: String,
) : ApiSpecificationBootstrapException(
        "API specification '$specPath' does not define any routable resources",
    )

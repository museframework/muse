package dev.museframework.connectors.db

internal class DbDriverLoadingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class DbParameterException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class DbScriptNotFoundException(
    path: String,
) : RuntimeException("Database script '$path' was not found")

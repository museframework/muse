package dev.museframework.connectors.file.internal

import dev.museframework.dsl.api.model.error.ErrorType

internal class FileConnectorException(
    val errorType: ErrorType,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

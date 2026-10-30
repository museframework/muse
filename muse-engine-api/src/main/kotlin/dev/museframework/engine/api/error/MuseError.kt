package dev.museframework.engine.api.error

import dev.museframework.dsl.api.model.error.ErrorType

data class MuseError(
    val description: String,
    val detailedDescription: String,
    val errorType: ErrorType,
    val cause: Throwable,
    val errorMessage: Any? = null,
    val childErrors: List<MuseError> = emptyList(),
    val sourceFile: String? = null,
    val sourceLine: Int? = null,
    val sourceClass: String? = null,
)

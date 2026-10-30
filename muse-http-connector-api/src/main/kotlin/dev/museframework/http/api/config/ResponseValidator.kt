package dev.museframework.http.api.config

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import dev.museframework.http.api.model.HttpErrorType
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.model.HttpResponseAttributes

sealed class ValidationResult {
    object Success : ValidationResult()

    data class Failure(
        val errorType: ErrorType,
        val description: String,
    ) : ValidationResult()
}

interface ResponseValidator {
    fun validate(
        response: HttpResponse,
        attributes: HttpResponseAttributes,
    ): ValidationResult
}

class DefaultResponseValidator : ResponseValidator {
    override fun validate(
        response: HttpResponse,
        attributes: HttpResponseAttributes,
    ): ValidationResult =
        if (response.statusCode in 0..399) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(
                errorType = HttpErrorType.fromStatusCode(response.statusCode),
                description = "HTTP ${response.statusCode}",
            )
        }
}

class SuccessStatusCodeValidator(
    private val statusCodes: IntRange = 0..399,
) : ResponseValidator {
    override fun validate(
        response: HttpResponse,
        attributes: HttpResponseAttributes,
    ): ValidationResult =
        if (response.statusCode in statusCodes) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(
                errorType = HttpErrorType.fromStatusCode(response.statusCode),
                description = "HTTP ${response.statusCode}",
            )
        }
}

class FailureStatusCodeValidator(
    private val statusCodes: IntRange = 400..599,
) : ResponseValidator {
    override fun validate(
        response: HttpResponse,
        attributes: HttpResponseAttributes,
    ): ValidationResult =
        if (response.statusCode in statusCodes) {
            ValidationResult.Failure(
                errorType = HttpErrorType.fromStatusCode(response.statusCode),
                description = "HTTP ${response.statusCode}",
            )
        } else {
            ValidationResult.Success
        }
}

class ExpressionResponseValidator(
    private val expression: String,
    private val expressionEvaluator: MuseExpressionEvaluator,
) : ResponseValidator {
    override fun validate(
        response: HttpResponse,
        attributes: HttpResponseAttributes,
    ): ValidationResult {
        val event =
            MuseEvent(
                attributes = TypedValue.of(attributes),
            )
        val result = expressionEvaluator.evaluate(expression, event)
        return if (result?.getOrNull() == true) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(
                errorType = HttpErrorType.CLIENT_ERROR,
                description = "Expression validation failed: $expression",
            )
        }
    }
}

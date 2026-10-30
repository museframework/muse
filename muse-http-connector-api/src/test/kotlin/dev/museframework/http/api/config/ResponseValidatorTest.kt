package dev.museframework.http.api.config

import dev.museframework.http.api.model.HttpErrorType
import dev.museframework.http.api.model.HttpResponse
import dev.museframework.http.api.model.HttpResponseAttributes
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.expression.MuseExpressionEvaluator
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResponseValidatorTest {
    private val validator = DefaultResponseValidator()
    private val attributes = HttpResponseAttributes(statusCode = 200)

    @Test
    fun `validate should return Success for status code 200`() {
        val response = HttpResponse(statusCode = 200)

        val result = validator.validate(response, attributes)

        assertEquals(ValidationResult.Success, result)
    }

    @Test
    fun `validate should return Success for status code 201`() {
        val response = HttpResponse(statusCode = 201)

        val result = validator.validate(response, attributes)

        assertEquals(ValidationResult.Success, result)
    }

    @Test
    fun `validate should return Success for status code 204`() {
        val response = HttpResponse(statusCode = 204)

        val result = validator.validate(response, attributes)

        assertEquals(ValidationResult.Success, result)
    }

    @Test
    fun `validate should return Success for status code 299`() {
        val response = HttpResponse(statusCode = 299)

        val result = validator.validate(response, attributes)

        assertEquals(ValidationResult.Success, result)
    }

    @Test
    fun `validate should return Success for redirect status code`() {
        val response = HttpResponse(statusCode = 302)

        assertEquals(ValidationResult.Success, validator.validate(response, attributes))
    }

    @Test
    fun `validate should return Failure for status code 400`() {
        val response = HttpResponse(statusCode = 400)

        val result = validator.validate(response, attributes)

        assertTrue(result is ValidationResult.Failure)
        val failure = result as ValidationResult.Failure
        assertEquals(HttpErrorType.BAD_REQUEST, failure.errorType)
        assertEquals("HTTP 400", failure.description)
    }

    @Test
    fun `validate should return Failure for status code 404`() {
        val response = HttpResponse(statusCode = 404)

        val result = validator.validate(response, attributes)

        assertTrue(result is ValidationResult.Failure)
        val failure = result as ValidationResult.Failure
        assertEquals(HttpErrorType.NOT_FOUND, failure.errorType)
        assertEquals("HTTP 404", failure.description)
    }

    @Test
    fun `validate should return Failure for status code 500`() {
        val response = HttpResponse(statusCode = 500)

        val result = validator.validate(response, attributes)

        assertTrue(result is ValidationResult.Failure)
        val failure = result as ValidationResult.Failure
        assertEquals(HttpErrorType.INTERNAL_SERVER_ERROR, failure.errorType)
        assertEquals("HTTP 500", failure.description)
    }
}

class SuccessStatusCodeValidatorTest {
    private val attributes = HttpResponseAttributes(statusCode = 200)

    @Test
    fun `validate should return Success for status code 200`() {
        val validator = SuccessStatusCodeValidator()
        val response = HttpResponse(statusCode = 200)

        val result = validator.validate(response, attributes)

        assertEquals(ValidationResult.Success, result)
    }

    @Test
    fun `validate should return Failure for status code 404`() {
        val validator = SuccessStatusCodeValidator()
        val response = HttpResponse(statusCode = 404)

        val result = validator.validate(response, attributes)

        assertTrue(result is ValidationResult.Failure)
        val failure = result as ValidationResult.Failure
        assertEquals(HttpErrorType.NOT_FOUND, failure.errorType)
        assertEquals("HTTP 404", failure.description)
    }

    @Test
    fun `validate should return Success for custom range 201 to 203`() {
        val validator = SuccessStatusCodeValidator(statusCodes = 201..203)
        val response201 = HttpResponse(statusCode = 201)
        val response202 = HttpResponse(statusCode = 202)
        val response203 = HttpResponse(statusCode = 203)

        assertEquals(ValidationResult.Success, validator.validate(response201, attributes))
        assertEquals(ValidationResult.Success, validator.validate(response202, attributes))
        assertEquals(ValidationResult.Success, validator.validate(response203, attributes))
    }

    @Test
    fun `validate should return Failure for status code 200 when custom range is 201 to 203`() {
        val validator = SuccessStatusCodeValidator(statusCodes = 201..203)
        val response = HttpResponse(statusCode = 200)

        val result = validator.validate(response, attributes)

        assertTrue(result is ValidationResult.Failure)
    }
}

class FailureStatusCodeValidatorTest {
    private val attributes = HttpResponseAttributes(statusCode = 200)

    @Test
    fun `validate should return Failure for status code 404`() {
        val validator = FailureStatusCodeValidator()
        val response = HttpResponse(statusCode = 404)

        val result = validator.validate(response, attributes)

        assertTrue(result is ValidationResult.Failure)
        val failure = result as ValidationResult.Failure
        assertEquals(HttpErrorType.NOT_FOUND, failure.errorType)
        assertEquals("HTTP 404", failure.description)
    }

    @Test
    fun `validate should return Failure for status code 500`() {
        val validator = FailureStatusCodeValidator()
        val response = HttpResponse(statusCode = 500)

        val result = validator.validate(response, attributes)

        assertTrue(result is ValidationResult.Failure)
        val failure = result as ValidationResult.Failure
        assertEquals(HttpErrorType.INTERNAL_SERVER_ERROR, failure.errorType)
        assertEquals("HTTP 500", failure.description)
    }

    @Test
    fun `validate should return Success for status code 200`() {
        val validator = FailureStatusCodeValidator()
        val response = HttpResponse(statusCode = 200)

        val result = validator.validate(response, attributes)

        assertEquals(ValidationResult.Success, result)
    }

    @Test
    fun `validate should return Failure for custom range 200 to 299`() {
        val validator = FailureStatusCodeValidator(statusCodes = 200..299)
        val response200 = HttpResponse(statusCode = 200)
        val response201 = HttpResponse(statusCode = 201)

        assertTrue(validator.validate(response200, attributes) is ValidationResult.Failure)
        assertTrue(validator.validate(response201, attributes) is ValidationResult.Failure)
    }

    @Test
    fun `validate should return Success for status code 404 when custom range is 200 to 299`() {
        val validator = FailureStatusCodeValidator(statusCodes = 200..299)
        val response = HttpResponse(statusCode = 404)

        val result = validator.validate(response, attributes)

        assertEquals(ValidationResult.Success, result)
    }
}

class ExpressionResponseValidatorTest {
    private val expressionEvaluator = mockk<MuseExpressionEvaluator>()

    @Test
    fun `validate should return Success when expression evaluates to true`() {
        val expression = "#[attributes.statusCode == 200]"
        val validator = ExpressionResponseValidator(expression, expressionEvaluator)
        val response = HttpResponse(statusCode = 200)
        val attributes = HttpResponseAttributes(statusCode = 200)
        every { expressionEvaluator.evaluate(expression, any<MuseEvent>()) } returns TypedValue.of(true)

        val result = validator.validate(response, attributes)

        assertEquals(ValidationResult.Success, result)
    }

    @Test
    fun `validate should return Failure when expression evaluates to false`() {
        val expression = "#[attributes.statusCode == 200]"
        val validator = ExpressionResponseValidator(expression, expressionEvaluator)
        val response = HttpResponse(statusCode = 404)
        val attributes = HttpResponseAttributes(statusCode = 404)
        every { expressionEvaluator.evaluate(expression, any<MuseEvent>()) } returns TypedValue.of(false)

        val result = validator.validate(response, attributes)

        assertTrue(result is ValidationResult.Failure)
        val failure = result as ValidationResult.Failure
        assertEquals(HttpErrorType.CLIENT_ERROR, failure.errorType)
        assertEquals("Expression validation failed: $expression", failure.description)
    }
}

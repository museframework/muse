package dev.museframework.apikit.routing

import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiParameter
import dev.museframework.http.api.model.HttpMethod
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestValidatorTest {
    @Nested
    @DisplayName("validateHeaders")
    inner class ValidateHeaders {
        @Test
        fun `should return empty list when all required headers are present`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredHeaders =
                        listOf(
                            ApiParameter(name = "Authorization", required = true),
                            ApiParameter(name = "X-Request-Id", required = true),
                        ),
                )
            val headers =
                mapOf(
                    "Authorization" to "Bearer token",
                    "X-Request-Id" to "12345",
                )

            val result = RequestValidator.validateHeaders(method, headers)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return missing header names when required headers are absent`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredHeaders =
                        listOf(
                            ApiParameter(name = "Authorization", required = true),
                            ApiParameter(name = "X-Request-Id", required = true),
                        ),
                )
            val headers = mapOf("Authorization" to "Bearer token")

            val result = RequestValidator.validateHeaders(method, headers)

            assertEquals(listOf("X-Request-Id"), result)
        }

        @Test
        fun `should return all missing headers when multiple required headers are absent`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredHeaders =
                        listOf(
                            ApiParameter(name = "Authorization", required = true),
                            ApiParameter(name = "X-Request-Id", required = true),
                            ApiParameter(name = "X-Api-Key", required = true),
                        ),
                )
            val headers = emptyMap<String, String>()

            val result = RequestValidator.validateHeaders(method, headers)

            assertEquals(
                listOf("Authorization", "X-Request-Id", "X-Api-Key"),
                result,
            )
        }

        @Test
        fun `should ignore optional headers`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredHeaders =
                        listOf(
                            ApiParameter(name = "Authorization", required = true),
                            ApiParameter(name = "X-Optional-Header", required = false),
                        ),
                )
            val headers = mapOf("Authorization" to "Bearer token")

            val result = RequestValidator.validateHeaders(method, headers)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return empty list when method has no required headers`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredHeaders = emptyList(),
                )
            val headers = emptyMap<String, String>()

            val result = RequestValidator.validateHeaders(method, headers)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should be case-sensitive for header names`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredHeaders =
                        listOf(
                            ApiParameter(name = "Authorization", required = true),
                        ),
                )
            val headers = mapOf("authorization" to "Bearer token")

            val result = RequestValidator.validateHeaders(method, headers)

            assertEquals(listOf("Authorization"), result)
        }
    }

    @Nested
    @DisplayName("validateQueryParams")
    inner class ValidateQueryParams {
        @Test
        fun `should return empty list when all required query params are present`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredQueryParams =
                        listOf(
                            ApiParameter(name = "page", required = true),
                            ApiParameter(name = "limit", required = true),
                        ),
                )
            val queryParams =
                mapOf(
                    "page" to "1",
                    "limit" to "10",
                )

            val result = RequestValidator.validateQueryParams(method, queryParams)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return missing param names when required query params are absent`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredQueryParams =
                        listOf(
                            ApiParameter(name = "page", required = true),
                            ApiParameter(name = "limit", required = true),
                        ),
                )
            val queryParams = mapOf("page" to "1")

            val result = RequestValidator.validateQueryParams(method, queryParams)

            assertEquals(listOf("limit"), result)
        }

        @Test
        fun `should return all missing params when multiple required query params are absent`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredQueryParams =
                        listOf(
                            ApiParameter(name = "page", required = true),
                            ApiParameter(name = "limit", required = true),
                            ApiParameter(name = "sort", required = true),
                        ),
                )
            val queryParams = emptyMap<String, String>()

            val result = RequestValidator.validateQueryParams(method, queryParams)

            assertEquals(
                listOf("page", "limit", "sort"),
                result,
            )
        }

        @Test
        fun `should ignore optional query params`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredQueryParams =
                        listOf(
                            ApiParameter(name = "page", required = true),
                            ApiParameter(name = "filter", required = false),
                        ),
                )
            val queryParams = mapOf("page" to "1")

            val result = RequestValidator.validateQueryParams(method, queryParams)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should return empty list when method has no required query params`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredQueryParams = emptyList(),
                )
            val queryParams = emptyMap<String, String>()

            val result = RequestValidator.validateQueryParams(method, queryParams)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `should be case-sensitive for query param names`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredQueryParams =
                        listOf(
                            ApiParameter(name = "Page", required = true),
                        ),
                )
            val queryParams = mapOf("page" to "1")

            val result = RequestValidator.validateQueryParams(method, queryParams)

            assertEquals(listOf("Page"), result)
        }
    }

    @Nested
    @DisplayName("combined validation")
    inner class CombinedValidation {
        @Test
        fun `should validate headers and query params independently`() {
            val method =
                ApiMethod(
                    method = HttpMethod.GET,
                    requiredHeaders =
                        listOf(
                            ApiParameter(name = "Authorization", required = true),
                        ),
                    requiredQueryParams =
                        listOf(
                            ApiParameter(name = "page", required = true),
                        ),
                )
            val headers = mapOf("Authorization" to "Bearer token")
            val queryParams = emptyMap<String, String>()

            val headerResult = RequestValidator.validateHeaders(method, headers)
            val queryResult = RequestValidator.validateQueryParams(method, queryParams)

            assertTrue(headerResult.isEmpty())
            assertEquals(listOf("page"), queryResult)
        }
    }
}

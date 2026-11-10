package dev.museframework.apikit.routing

import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiParameter
import dev.museframework.http.api.model.HttpMethod
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    @Nested
    @DisplayName("Content-Type validation")
    inner class ContentTypeValidation {
        private val binary = ApiMethod(method = HttpMethod.POST, contentTypes = listOf("application/octet-stream"))

        @Test
        fun `matches Content-Type name and media type without case sensitivity`() {
            assertTrue(RequestValidator.acceptsContentType(binary, mapOf("content-type" to "Application/Octet-Stream")))
            assertTrue(RequestValidator.acceptsContentType(binary, mapOf("CONTENT-TYPE" to "application/octet-stream")))
        }

        @Test
        fun `accepts valid extra request parameters for a bare RAML media type`() {
            assertTrue(RequestValidator.acceptsContentType(binary, mapOf("Content-Type" to "application/octet-stream; charset=UTF-8")))
            assertTrue(RequestValidator.acceptsContentType(binary, mapOf("Content-Type" to "application/octet-stream; note=\"a;b\"")))
        }

        @Test
        fun `rejects unsupported or missing Content-Type`() {
            assertFalse(RequestValidator.acceptsContentType(binary, mapOf("Content-Type" to "application/json")))
            assertFalse(RequestValidator.acceptsContentType(binary, emptyMap()))
            assertFalse(RequestValidator.acceptsContentType(binary, mapOf("Accept" to "application/octet-stream")))
        }

        @Test
        fun `rejects malformed and ambiguous headers`() {
            listOf(
                "application/octet-stream; charset",
                "application/octet-stream; charset=",
                "application/octet-stream, application/json",
                "application/octet-stream; charset=\"unterminated",
                "application/octet-stream/extra",
            ).forEach { header ->
                assertFalse(RequestValidator.acceptsContentType(binary, mapOf("Content-Type" to header)), header)
            }
            assertFalse(
                RequestValidator.acceptsContentType(
                    binary,
                    mapOf("Content-Type" to "application/octet-stream", "content-type" to "application/json"),
                ),
            )
        }

        @Test
        fun `long quoted parameter values do not recurse or exhaust the stack`() {
            listOf(4_000, 32_768).forEach { length ->
                val value = "application/octet-stream; note=\"${"a".repeat(length)}\""
                assertTrue(RequestValidator.acceptsContentType(binary, mapOf("Content-Type" to value)), "length=$length")
                assertFalse(RequestValidator.acceptsContentType(binary, mapOf("Content-Type" to "$value\n")))
            }
        }

        @Test
        fun `accepts RFC quoted HTAB obs-text escapes and OWS`() {
            val valid = listOf(
                "application/octet-stream; note=\"a\tb\"",
                "application/octet-stream; note=\"café\"",
                "application/octet-stream; note=\"\u00FF\"",
                "application/octet-stream; note=\"a\\\"b\"",
                "application/octet-stream; note=\"a\\\\b\"",
                " \tapplication/octet-stream\t ; \tcharset=UTF-8\t",
                "application/octet-stream;;note=\"a;b\";",
            )
            valid.forEach { value ->
                assertTrue(RequestValidator.acceptsContentType(binary, mapOf("Content-Type" to value)), value)
            }
        }

        @Test
        fun `rejects whitespace around parameter equals and non OWS controls`() {
            val invalid = listOf(
                "application/octet-stream; charset =UTF-8",
                "application/octet-stream; charset= UTF-8",
                "application/octet-stream; charset\t=UTF-8",
                "application/octet-stream; charset=\tUTF-8",
                "application/octet-stream;\ncharset=UTF-8",
                "application/octet-stream;\rcharset=UTF-8",
                "\napplication/octet-stream",
                "application/octet-stream\n",
                "application/octet-stream; note=\"a\nb\"",
                "application/octet-stream; note=\"a\u0000b\"",
                "application/octet-stream; note=\"a\u0100b\"",
                "application/octet-stream; note=\"unfinished\\",
            )
            invalid.forEach { value ->
                assertFalse(RequestValidator.acceptsContentType(binary, mapOf("Content-Type" to value)), value)
            }
        }

        @Test
        fun `accepts an operation without request media constraints`() {
            val get = ApiMethod(method = HttpMethod.GET)
            assertTrue(RequestValidator.acceptsContentType(get, emptyMap()))
            assertTrue(RequestValidator.acceptsContentType(get, mapOf("Content-Type" to "invalid value")))
        }

        @Test
        fun `matches any explicitly declared request media type`() {
            val method = ApiMethod(
                method = HttpMethod.POST,
                contentTypes = listOf("application/json", "application/xml"),
            )
            assertTrue(RequestValidator.acceptsContentType(method, mapOf("Content-Type" to "application/xml")))
            assertFalse(RequestValidator.acceptsContentType(method, mapOf("Content-Type" to "text/plain")))
        }
    }
}

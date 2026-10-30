package dev.museframework.apikit.routing

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("UriPatternMatcher")
class UriPatternMatcherTest {
    @Nested
    @DisplayName("basic matching")
    inner class BasicMatching {
        @Test
        fun `should match exact path without parameters`() {
            val result = UriPatternMatcher.match("/users", "/users")

            assertTrue(result.matched)
            assertEquals(emptyMap<String, String>(), result.pathParams)
        }

        @Test
        fun `should match nested path without parameters`() {
            val result = UriPatternMatcher.match("/api/v1/users", "/api/v1/users")

            assertTrue(result.matched)
            assertEquals(emptyMap<String, String>(), result.pathParams)
        }

        @Test
        fun `should not match different paths`() {
            val result = UriPatternMatcher.match("/users", "/orders")

            assertFalse(result.matched)
        }

        @Test
        fun `should not match when segment count differs`() {
            val result = UriPatternMatcher.match("/users", "/users/profile")

            assertFalse(result.matched)
        }
    }

    @Nested
    @DisplayName("single parameter extraction")
    inner class SingleParameterExtraction {
        @Test
        fun `should extract single path parameter`() {
            val result = UriPatternMatcher.match("/users/{id}", "/users/123")

            assertTrue(result.matched)
            assertEquals(mapOf("id" to "123"), result.pathParams)
        }

        @Test
        fun `should extract parameter with UUID value`() {
            val result = UriPatternMatcher.match("/users/{id}", "/users/550e8400-e29b-41d4-a716-446655440000")

            assertTrue(result.matched)
            assertEquals(mapOf("id" to "550e8400-e29b-41d4-a716-446655440000"), result.pathParams)
        }

        @Test
        fun `should extract parameter with alphanumeric value`() {
            val result = UriPatternMatcher.match("/items/{code}", "/items/ABC123")

            assertTrue(result.matched)
            assertEquals(mapOf("code" to "ABC123"), result.pathParams)
        }
    }

    @Nested
    @DisplayName("multiple parameters extraction")
    inner class MultipleParametersExtraction {
        @Test
        fun `should extract multiple path parameters`() {
            val result = UriPatternMatcher.match("/users/{userId}/orders/{orderId}", "/users/123/orders/456")

            assertTrue(result.matched)
            assertEquals(mapOf("userId" to "123", "orderId" to "456"), result.pathParams)
        }

        @Test
        fun `should extract three parameters`() {
            val result =
                UriPatternMatcher.match(
                    "/orgs/{orgId}/teams/{teamId}/members/{memberId}",
                    "/orgs/org1/teams/team2/members/member3",
                )

            assertTrue(result.matched)
            assertEquals(
                mapOf("orgId" to "org1", "teamId" to "team2", "memberId" to "member3"),
                result.pathParams,
            )
        }

        @Test
        fun `should handle mixed static and dynamic segments`() {
            val result = UriPatternMatcher.match("/api/v1/users/{id}/profile", "/api/v1/users/42/profile")

            assertTrue(result.matched)
            assertEquals(mapOf("id" to "42"), result.pathParams)
        }
    }

    @Nested
    @DisplayName("trailing slashes")
    inner class TrailingSlashes {
        @Test
        fun `should match with trailing slash in path`() {
            val result = UriPatternMatcher.match("/users/{id}", "/users/123/")

            assertTrue(result.matched)
            assertEquals(mapOf("id" to "123"), result.pathParams)
        }

        @Test
        fun `should match with trailing slash in pattern`() {
            val result = UriPatternMatcher.match("/users/{id}/", "/users/123")

            assertTrue(result.matched)
            assertEquals(mapOf("id" to "123"), result.pathParams)
        }

        @Test
        fun `should match with trailing slashes in both`() {
            val result = UriPatternMatcher.match("/users/{id}/", "/users/123/")

            assertTrue(result.matched)
            assertEquals(mapOf("id" to "123"), result.pathParams)
        }
    }

    @Nested
    @DisplayName("empty segments and root path")
    inner class EmptySegmentsAndRootPath {
        @Test
        fun `should match root path`() {
            val result = UriPatternMatcher.match("/", "/")

            assertTrue(result.matched)
            assertEquals(emptyMap<String, String>(), result.pathParams)
        }

        @Test
        fun `should match empty path to root`() {
            val result = UriPatternMatcher.match("", "")

            assertTrue(result.matched)
            assertEquals(emptyMap<String, String>(), result.pathParams)
        }

        @Test
        fun `should not match root against non-root`() {
            val result = UriPatternMatcher.match("/", "/users")

            assertFalse(result.matched)
        }
    }

    @Nested
    @DisplayName("special characters and encoding")
    inner class SpecialCharactersAndEncoding {
        @Test
        fun `should handle URL encoded values`() {
            val result = UriPatternMatcher.match("/search/{query}", "/search/hello%20world")

            assertTrue(result.matched)
            assertEquals(mapOf("query" to "hello world"), result.pathParams)
        }

        @Test
        fun `should handle special characters in path`() {
            val result = UriPatternMatcher.match("/files/{filename}", "/files/report-2024.pdf")

            assertTrue(result.matched)
            assertEquals(mapOf("filename" to "report-2024.pdf"), result.pathParams)
        }

        @Test
        fun `should handle encoded slashes`() {
            val result = UriPatternMatcher.match("/path/{segment}", "/path/a%2Fb")

            assertTrue(result.matched)
            assertEquals(mapOf("segment" to "a/b"), result.pathParams)
        }

        @Test
        fun `should handle plus sign encoding`() {
            val result = UriPatternMatcher.match("/calc/{expr}", "/calc/2%2B2")

            assertTrue(result.matched)
            assertEquals(mapOf("expr" to "2+2"), result.pathParams)
        }
    }

    @Nested
    @DisplayName("edge cases")
    inner class EdgeCases {
        @Test
        fun `should not match when parameter segment is empty`() {
            val result = UriPatternMatcher.match("/users/{id}", "/users/")

            assertFalse(result.matched)
        }

        @Test
        fun `should handle consecutive parameters`() {
            val result = UriPatternMatcher.match("/{a}/{b}", "/foo/bar")

            assertTrue(result.matched)
            assertEquals(mapOf("a" to "foo", "b" to "bar"), result.pathParams)
        }

        @Test
        fun `should handle parameter at start`() {
            val result = UriPatternMatcher.match("/{version}/users", "/v2/users")

            assertTrue(result.matched)
            assertEquals(mapOf("version" to "v2"), result.pathParams)
        }

        @Test
        fun `should handle parameter at end`() {
            val result = UriPatternMatcher.match("/users/{id}", "/users/abc")

            assertTrue(result.matched)
            assertEquals(mapOf("id" to "abc"), result.pathParams)
        }

        @Test
        fun `should handle numeric values`() {
            val result = UriPatternMatcher.match("/page/{num}", "/page/12345")

            assertTrue(result.matched)
            assertEquals(mapOf("num" to "12345"), result.pathParams)
        }

        @Test
        fun `should handle case sensitive matching`() {
            val result = UriPatternMatcher.match("/Users/{id}", "/users/123")

            assertFalse(result.matched)
        }
    }

    @Nested
    @DisplayName("extractParamNames")
    inner class ExtractParamNames {
        @Test
        fun `should extract single parameter name`() {
            val names = UriPatternMatcher.extractParamNames("/users/{id}")

            assertEquals(listOf("id"), names)
        }

        @Test
        fun `should extract multiple parameter names`() {
            val names = UriPatternMatcher.extractParamNames("/users/{userId}/orders/{orderId}")

            assertEquals(listOf("userId", "orderId"), names)
        }

        @Test
        fun `should return empty list for no parameters`() {
            val names = UriPatternMatcher.extractParamNames("/users/profile")

            assertEquals(emptyList<String>(), names)
        }

        @Test
        fun `should extract parameter names from root pattern`() {
            val names = UriPatternMatcher.extractParamNames("/{id}")

            assertEquals(listOf("id"), names)
        }
    }
}

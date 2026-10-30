package dev.museframework.apikit.routing

import dev.museframework.http.api.model.HttpMethod
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FlowResolverTest {
    private val resolver = FlowResolver

    @Nested
    @DisplayName("Basic flow name generation")
    inner class BasicFlowNameGeneration {
        @Test
        fun `should generate flow name for GET without content type`() {
            val result = resolver.resolve(HttpMethod.GET, "/users", null, "apiSpec")
            assertEquals("get:\\users:apiSpec", result)
        }

        @Test
        fun `should generate flow name for POST without content type`() {
            val result = resolver.resolve(HttpMethod.POST, "/users", null, "apiSpec")
            assertEquals("post:\\users:apiSpec", result)
        }

        @Test
        fun `should generate flow name for PUT without content type`() {
            val result = resolver.resolve(HttpMethod.PUT, "/users/123", null, "apiSpec")
            assertEquals("put:\\users\\123:apiSpec", result)
        }

        @Test
        fun `should generate flow name for DELETE without content type`() {
            val result = resolver.resolve(HttpMethod.DELETE, "/users/123", null, "apiSpec")
            assertEquals("delete:\\users\\123:apiSpec", result)
        }

        @Test
        fun `should generate flow name for PATCH without content type`() {
            val result = resolver.resolve(HttpMethod.PATCH, "/users/123", null, "apiSpec")
            assertEquals("patch:\\users\\123:apiSpec", result)
        }
    }

    @Nested
    @DisplayName("Path parameter handling")
    inner class PathParameterHandling {
        @Test
        fun `should preserve path parameters in flow name`() {
            val result = resolver.resolve(HttpMethod.GET, "/users/{id}", null, "apiSpec")
            assertEquals("get:\\users\\{id}:apiSpec", result)
        }

        @Test
        fun `should handle multiple path parameters`() {
            val result = resolver.resolve(HttpMethod.GET, "/users/{userId}/posts/{postId}", null, "apiSpec")
            assertEquals("get:\\users\\{userId}\\posts\\{postId}:apiSpec", result)
        }

        @Test
        fun `should handle mixed static and parameter segments`() {
            val result = resolver.resolve(HttpMethod.GET, "/api/v1/users/{id}/profile", null, "apiSpec")
            assertEquals("get:\\api\\v1\\users\\{id}\\profile:apiSpec", result)
        }
    }

    @Nested
    @DisplayName("Content type handling")
    inner class ContentTypeHandling {
        @Test
        fun `should include content type when provided`() {
            val result = resolver.resolve(HttpMethod.POST, "/users", "application/json", "apiSpec")
            assertEquals("post:\\users:application\\json:apiSpec", result)
        }

        @Test
        fun `should handle content type with subtype`() {
            val result = resolver.resolve(HttpMethod.POST, "/users", "application/xml", "apiSpec")
            assertEquals("post:\\users:application\\xml:apiSpec", result)
        }

        @Test
        fun `should handle complex content type`() {
            val result = resolver.resolve(HttpMethod.POST, "/users", "application/vnd.api+json", "apiSpec")
            assertEquals("post:\\users:application\\vnd.api+json:apiSpec", result)
        }

        @Test
        fun `should omit content type when null`() {
            val result = resolver.resolve(HttpMethod.GET, "/users", null, "apiSpec")
            assertEquals("get:\\users:apiSpec", result)
        }
    }

    @Nested
    @DisplayName("Combined scenarios")
    inner class CombinedScenarios {
        @Test
        fun `should handle path parameters with content type`() {
            val result = resolver.resolve(HttpMethod.PUT, "/users/{id}", "application/json", "apiSpec")
            assertEquals("put:\\users\\{id}:application\\json:apiSpec", result)
        }

        @Test
        fun `should handle root path without content type`() {
            val result = resolver.resolve(HttpMethod.GET, "/", null, "apiSpec")
            assertEquals("get:\\:apiSpec", result)
        }

        @Test
        fun `should handle root path with content type`() {
            val result = resolver.resolve(HttpMethod.POST, "/", "application/json", "apiSpec")
            assertEquals("post:\\:application\\json:apiSpec", result)
        }

        @Test
        fun `should handle deeply nested path`() {
            val result = resolver.resolve(HttpMethod.GET, "/a/b/c/d/e/f", null, "apiSpec")
            assertEquals("get:\\a\\b\\c\\d\\e\\f:apiSpec", result)
        }

        @Test
        fun `should handle all HTTP methods`() {
            val methods =
                listOf(
                    HttpMethod.GET to "get",
                    HttpMethod.POST to "post",
                    HttpMethod.PUT to "put",
                    HttpMethod.DELETE to "delete",
                    HttpMethod.PATCH to "patch",
                    HttpMethod.HEAD to "head",
                    HttpMethod.OPTIONS to "options",
                    HttpMethod.ANY to "any",
                )

            methods.forEach { (method, expectedPrefix) ->
                val result = resolver.resolve(method, "/test", null, "apiSpec")
                assertEquals("$expectedPrefix:\\test:apiSpec", result)
            }
        }
    }

    @Nested
    @DisplayName("Config name variations")
    inner class ConfigNameVariations {
        @Test
        fun `should handle different config names`() {
            val result1 = resolver.resolve(HttpMethod.GET, "/users", null, "myApi")
            assertEquals("get:\\users:myApi", result1)

            val result2 = resolver.resolve(HttpMethod.GET, "/users", null, "api-v2")
            assertEquals("get:\\users:api-v2", result2)

            val result3 = resolver.resolve(HttpMethod.GET, "/users", null, "my_api_spec")
            assertEquals("get:\\users:my_api_spec", result3)
        }
    }
}

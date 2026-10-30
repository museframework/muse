package dev.museframework.apikit.routing

import dev.museframework.apikit.api.model.ApiMethod
import dev.museframework.apikit.api.model.ApiResource
import dev.museframework.apikit.api.model.ApiSpecification
import dev.museframework.apikit.api.routing.RoutingResult
import dev.museframework.http.api.model.HttpMethod
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("RoutingTable")
class RoutingTableTest {
    @Nested
    @DisplayName("resolve with matching path and method")
    inner class MatchingPathAndMethod {
        @Test
        fun `should return Match when path and method match exactly`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("/users", HttpMethod.GET)

            assertTrue(result is RoutingResult.Match)
            assertEquals("get:\\users:myApi", (result as RoutingResult.Match).flowName)
            assertEquals(emptyMap<String, String>(), result.pathParams)
        }

        @Test
        fun `should return Match with path parameters`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users/{id}",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("/users/123", HttpMethod.GET)

            assertTrue(result is RoutingResult.Match)
            val match = result as RoutingResult.Match
            assertEquals("get:\\users\\{id}:myApi", match.flowName)
            assertEquals(mapOf("id" to "123"), match.pathParams)
        }

        @Test
        fun `should return Match with multiple path parameters`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users/{userId}/posts/{postId}",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("/users/42/posts/100", HttpMethod.GET)

            assertTrue(result is RoutingResult.Match)
            val match = result as RoutingResult.Match
            assertEquals(mapOf("userId" to "42", "postId" to "100"), match.pathParams)
        }
    }

    @Nested
    @DisplayName("resolve with ANY method")
    inner class AnyMethod {
        @Test
        fun `should match any HTTP method when ANY is specified`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/health",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.ANY),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val resultGet = table.resolve("/health", HttpMethod.GET)
            val resultPost = table.resolve("/health", HttpMethod.POST)
            val resultPut = table.resolve("/health", HttpMethod.PUT)

            assertTrue(resultGet is RoutingResult.Match)
            assertTrue(resultPost is RoutingResult.Match)
            assertTrue(resultPut is RoutingResult.Match)
        }
    }

    @Nested
    @DisplayName("resolve with wrong method")
    inner class WrongMethod {
        @Test
        fun `should return MethodNotAllowed when path matches but method does not`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                        ApiMethod(method = HttpMethod.POST),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("/users", HttpMethod.DELETE)

            assertTrue(result is RoutingResult.MethodNotAllowed)
            val methodNotAllowed = result as RoutingResult.MethodNotAllowed
            assertEquals("/users", methodNotAllowed.path)
            assertEquals(listOf(HttpMethod.GET, HttpMethod.POST), methodNotAllowed.allowedMethods)
        }
    }

    @Nested
    @DisplayName("resolve with unknown path")
    inner class UnknownPath {
        @Test
        fun `should return NotFound when path does not match any route`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("/products", HttpMethod.GET)

            assertTrue(result is RoutingResult.NotFound)
            assertEquals("/products", (result as RoutingResult.NotFound).path)
        }

        @Test
        fun `should return NotFound when path has different segment count`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users/{id}",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("/users", HttpMethod.GET)

            assertTrue(result is RoutingResult.NotFound)
        }
    }

    @Nested
    @DisplayName("resolve with base path")
    inner class BasePath {
        @Test
        fun `should prepend base path to resource path`() {
            val spec =
                ApiSpecification(
                    basePath = "/api/v1",
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("/api/v1/users", HttpMethod.GET)

            assertTrue(result is RoutingResult.Match)
            val match = result as RoutingResult.Match
            assertEquals("get:\\api\\v1\\users:myApi", match.flowName)
        }

        @Test
        fun `should not duplicate slash when base path is root`() {
            val spec =
                ApiSpecification(
                    basePath = "/",
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("/users", HttpMethod.GET)

            assertTrue(result is RoutingResult.Match)
            val match = result as RoutingResult.Match
            assertEquals("get:\\users:myApi", match.flowName)
        }
    }

    @Nested
    @DisplayName("flow name generation")
    inner class FlowNameGeneration {
        @Test
        fun `should generate flow name with method, path, and config name`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/products/{id}",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.PUT),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "productApi")

            val result = table.resolve("/products/999", HttpMethod.PUT)

            assertTrue(result is RoutingResult.Match)
            assertEquals("put:\\products\\{id}:productApi", (result as RoutingResult.Match).flowName)
        }
    }

    @Nested
    @DisplayName("path normalization")
    inner class PathNormalization {
        @Test
        fun `should handle path without leading slash`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("users", HttpMethod.GET)

            assertTrue(result is RoutingResult.Match)
        }

        @Test
        fun `should handle URL-encoded path parameters`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/search/{query}",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val result = table.resolve("/search/hello%20world", HttpMethod.GET)

            assertTrue(result is RoutingResult.Match)
            val match = result as RoutingResult.Match
            assertEquals("hello world", match.pathParams["query"])
        }
    }

    @Nested
    @DisplayName("multiple resources")
    inner class MultipleResources {
        @Test
        fun `should match first matching resource`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                            ApiResource(
                                path = "/products",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val resultUsers = table.resolve("/users", HttpMethod.GET)
            val resultProducts = table.resolve("/products", HttpMethod.GET)

            assertTrue(resultUsers is RoutingResult.Match)
            assertTrue(resultProducts is RoutingResult.Match)
            assertEquals("get:\\users:myApi", (resultUsers as RoutingResult.Match).flowName)
            assertEquals("get:\\products:myApi", (resultProducts as RoutingResult.Match).flowName)
        }

        @Test
        fun `should match specific path before parameterized path`() {
            val spec =
                ApiSpecification(
                    resources =
                        listOf(
                            ApiResource(
                                path = "/users/me",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                            ApiResource(
                                path = "/users/{id}",
                                methods =
                                    listOf(
                                        ApiMethod(method = HttpMethod.GET),
                                    ),
                            ),
                        ),
                )
            val table = RoutingTable(spec, "myApi")

            val resultMe = table.resolve("/users/me", HttpMethod.GET)
            val resultId = table.resolve("/users/123", HttpMethod.GET)

            assertTrue(resultMe is RoutingResult.Match)
            assertTrue(resultId is RoutingResult.Match)
            assertEquals("get:\\users\\me:myApi", (resultMe as RoutingResult.Match).flowName)
            assertEquals(mapOf("id" to "123"), (resultId as RoutingResult.Match).pathParams)
        }
    }
}

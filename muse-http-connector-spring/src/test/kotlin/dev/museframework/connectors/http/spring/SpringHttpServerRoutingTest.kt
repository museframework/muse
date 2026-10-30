package dev.museframework.connectors.http.spring

import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpRequest
import dev.museframework.http.api.model.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.function.ServerRequest

class SpringHttpServerRoutingTest {
    @Test
    fun `trailing wildcard matches nested paths under a prefix`() {
        val server = SpringHttpServer()
        server.register("/api/*", listOf(HttpMethod.GET)) { HttpResponse(statusCode = 204) }
        val request = ServerRequest.create(MockHttpServletRequest("GET", "/api/tests/run-1/status"), emptyList())

        val response = checkNotNull(server.handle(request)).handle(request)

        assertEquals(204, response.statusCode().value())
    }

    @Test
    fun `route variables use the application path when a servlet context path is present`() {
        val request = routeRequest("/ctx/items/42", contextPath = "/ctx")

        assertEquals(mapOf("id" to "42"), request.uriParams)
        assertEquals("/ctx/items/42", request.requestPath)
        assertEquals("/ctx/items/42", request.rawRequestPath)
    }

    @Test
    fun `route variables decode after extraction without splitting encoded slashes or changing plus signs`() {
        val request = routeRequest("/items/A%2FB%252F%20C+plus")

        assertEquals(mapOf("id" to "A/B%2F C+plus"), request.uriParams)
        assertEquals("/items/A/B%2F C+plus", request.requestPath)
        assertEquals("/items/A%2FB%252F%20C+plus", request.rawRequestPath)
    }

    private fun routeRequest(rawPath: String, contextPath: String = ""): HttpRequest {
        val server = SpringHttpServer()
        lateinit var captured: HttpRequest
        server.register("/items/{id}", listOf(HttpMethod.GET)) { request ->
            captured = request
            HttpResponse(statusCode = 204)
        }
        val servletRequest = MockHttpServletRequest("GET", rawPath).apply { setContextPath(contextPath) }
        val request = ServerRequest.create(servletRequest, emptyList())
        val response = checkNotNull(server.handle(request)).handle(request)
        assertEquals(204, response.statusCode().value())
        return captured
    }
}

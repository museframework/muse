package dev.museframework.http.api.model

import dev.museframework.dsl.api.model.error.ErrorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpErrorTypeTest {

    @Test
    fun `namespace should be HTTP`() {
        assertEquals("HTTP", HttpErrorType.NAMESPACE)
    }

    @Test
    fun `BAD_REQUEST should have HTTP namespace`() {
        assertEquals("HTTP", HttpErrorType.BAD_REQUEST.namespace)
        assertEquals("BAD_REQUEST", HttpErrorType.BAD_REQUEST.identifier)
    }

    @Test
    fun `NOT_FOUND should have HTTP namespace`() {
        assertEquals("HTTP", HttpErrorType.NOT_FOUND.namespace)
        assertEquals("NOT_FOUND", HttpErrorType.NOT_FOUND.identifier)
    }

    @Test
    fun `fromStatusCode should map 400 to BAD_REQUEST`() {
        assertEquals(HttpErrorType.BAD_REQUEST, HttpErrorType.fromStatusCode(400))
    }

    @Test
    fun `fromStatusCode should map 401 to UNAUTHORIZED`() {
        assertEquals(HttpErrorType.UNAUTHORIZED, HttpErrorType.fromStatusCode(401))
    }

    @Test
    fun `fromStatusCode should map 403 to FORBIDDEN`() {
        assertEquals(HttpErrorType.FORBIDDEN, HttpErrorType.fromStatusCode(403))
    }

    @Test
    fun `fromStatusCode should map 404 to NOT_FOUND`() {
        assertEquals(HttpErrorType.NOT_FOUND, HttpErrorType.fromStatusCode(404))
    }

    @Test
    fun `fromStatusCode should map 405 to METHOD_NOT_ALLOWED`() {
        assertEquals(HttpErrorType.METHOD_NOT_ALLOWED, HttpErrorType.fromStatusCode(405))
    }

    @Test
    fun `fromStatusCode should map 406 to NOT_ACCEPTABLE`() {
        assertEquals(HttpErrorType.NOT_ACCEPTABLE, HttpErrorType.fromStatusCode(406))
    }

    @Test
    fun `fromStatusCode should map 408 to REQUEST_TIMEOUT`() {
        assertEquals(HttpErrorType.REQUEST_TIMEOUT, HttpErrorType.fromStatusCode(408))
    }

    @Test
    fun `fromStatusCode should map 409 to CONFLICT`() {
        assertEquals(HttpErrorType.CONFLICT, HttpErrorType.fromStatusCode(409))
    }

    @Test
    fun `fromStatusCode should map 410 to GONE`() {
        assertEquals(HttpErrorType.GONE, HttpErrorType.fromStatusCode(410))
    }

    @Test
    fun `fromStatusCode should map 415 to UNSUPPORTED_MEDIA`() {
        assertEquals(HttpErrorType.UNSUPPORTED_MEDIA, HttpErrorType.fromStatusCode(415))
    }

    @Test
    fun `fromStatusCode should map 429 to TOO_MANY_REQUESTS`() {
        assertEquals(HttpErrorType.TOO_MANY_REQUESTS, HttpErrorType.fromStatusCode(429))
    }

    @Test
    fun `fromStatusCode should map 500 to INTERNAL_SERVER_ERROR`() {
        assertEquals(HttpErrorType.INTERNAL_SERVER_ERROR, HttpErrorType.fromStatusCode(500))
    }

    @Test
    fun `fromStatusCode should map 503 to SERVICE_UNAVAILABLE`() {
        assertEquals(HttpErrorType.SERVICE_UNAVAILABLE, HttpErrorType.fromStatusCode(503))
    }

    @Test
    fun `fromStatusCode should map 504 to GATEWAY_TIMEOUT`() {
        assertEquals(HttpErrorType.GATEWAY_TIMEOUT, HttpErrorType.fromStatusCode(504))
    }

    @Test
    fun `fromStatusCode should map unknown 4xx to CLIENT_ERROR`() {
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(418))
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(499))
    }

    @Test
    fun `fromStatusCode should map unknown 5xx to SERVER_ERROR`() {
        assertEquals(HttpErrorType.SERVER_ERROR, HttpErrorType.fromStatusCode(501))
        assertEquals(HttpErrorType.SERVER_ERROR, HttpErrorType.fromStatusCode(599))
    }

    @Test
    fun `fromStatusCode should map non-4xx-5xx to CLIENT_ERROR as fallback`() {
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(200))
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(300))
    }

    @Test
    fun `should return CLIENT_ERROR for negative status code`() {
        val result = HttpErrorType.fromStatusCode(-1)
        assertEquals(HttpErrorType.CLIENT_ERROR, result)
    }

    @Test
    fun `should return CLIENT_ERROR for status code 0`() {
        val result = HttpErrorType.fromStatusCode(0)
        assertEquals(HttpErrorType.CLIENT_ERROR, result)
    }

    @Test
    fun `should return CLIENT_ERROR for 1xx informational status codes`() {
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(100))
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(101))
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(199))
    }

    @Test
    fun `should return CLIENT_ERROR for 3xx redirect status codes`() {
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(301))
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(302))
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(304))
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(307))
        assertEquals(HttpErrorType.CLIENT_ERROR, HttpErrorType.fromStatusCode(308))
    }

    @Test
    fun `CLIENT_ERROR should have parent ANY`() {
        assertEquals(HttpErrorType.ANY, HttpErrorType.CLIENT_ERROR.parent)
    }

    @Test
    fun `SERVER_ERROR should have parent ANY`() {
        assertEquals(HttpErrorType.ANY, HttpErrorType.SERVER_ERROR.parent)
    }

    @Test
    fun `ANY should be part of the Mule error hierarchy`() {
        assertEquals(ErrorType.Mule.ANY, HttpErrorType.ANY.parent)
    }
}

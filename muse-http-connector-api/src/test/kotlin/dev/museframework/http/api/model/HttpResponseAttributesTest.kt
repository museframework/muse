package dev.museframework.http.api.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HttpResponseAttributesTest {

    @Test
    fun `should create HttpResponseAttributes with all fields`() {
        val response = HttpResponseAttributes(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            reasonPhrase = "OK"
        )

        assertEquals(200, response.statusCode)
        assertEquals(mapOf("Content-Type" to "application/json"), response.headers)
        assertEquals("OK", response.reasonPhrase)
    }

    @Test
    fun `should create HttpResponseAttributes with default empty headers`() {
        val response = HttpResponseAttributes(
            statusCode = 404,
            reasonPhrase = "Not Found"
        )

        assertEquals(404, response.statusCode)
        assertEquals(emptyMap<String, String>(), response.headers)
        assertEquals("Not Found", response.reasonPhrase)
    }

    @Test
    fun `should create HttpResponseAttributes with default empty reasonPhrase`() {
        val response = HttpResponseAttributes(
            statusCode = 500,
            headers = mapOf("Content-Type" to "text/plain")
        )

        assertEquals(500, response.statusCode)
        assertEquals(mapOf("Content-Type" to "text/plain"), response.headers)
        assertEquals("", response.reasonPhrase)
    }

    @Test
    fun `should create HttpResponseAttributes with all defaults`() {
        val response = HttpResponseAttributes(
            statusCode = 204
        )

        assertEquals(204, response.statusCode)
        assertEquals(emptyMap<String, String>(), response.headers)
        assertEquals("", response.reasonPhrase)
    }

    @Test
    fun `should support data class copy function`() {
        val original = HttpResponseAttributes(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            reasonPhrase = "OK"
        )

        val modified = original.copy(statusCode = 201, reasonPhrase = "Created")

        assertEquals(201, modified.statusCode)
        assertEquals(mapOf("Content-Type" to "application/json"), modified.headers)
        assertEquals("Created", modified.reasonPhrase)
    }

    @Test
    fun `should support data class equals and hashCode`() {
        val response1 = HttpResponseAttributes(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            reasonPhrase = "OK"
        )
        val response2 = HttpResponseAttributes(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            reasonPhrase = "OK"
        )

        assertEquals(response1, response2)
        assertEquals(response1.hashCode(), response2.hashCode())
    }

    @Test
    fun `should support data class toString`() {
        val response = HttpResponseAttributes(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            reasonPhrase = "OK"
        )

        val stringRepresentation = response.toString()

        assertTrue(stringRepresentation.contains("HttpResponseAttributes"))
        assertTrue(stringRepresentation.contains("statusCode=200"))
        assertTrue(stringRepresentation.contains("reasonPhrase=OK"))
    }
}

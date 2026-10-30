package dev.museframework.test.http

import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

abstract class HttpListenerContract {
    @Test
    fun `GET returns the exact listener response`() {
        given()
            .`when`().get("/api/hello")
            .then()
            .statusCode(200)
            .contentType(containsString("text/plain"))
            .body(equalTo("hello world"))
    }

    @Test
    fun `POST accepts JSON and returns the exact listener response`() {
        given()
            .contentType("application/json")
            .body("""{"message":"test input"}""")
            .`when`().post("/api/hello")
            .then()
            .statusCode(200)
            .body(equalTo("hello world"))
    }

    @Test
    fun `POST accepts plain text and returns the exact listener response`() {
        given()
            .contentType("text/plain")
            .body("plain text body")
            .`when`().post("/api/hello")
            .then()
            .statusCode(200)
            .body(equalTo("hello world"))
    }

    @Test
    fun `unsupported method is not routed`() {
        given()
            .`when`().put("/api/hello")
            .then()
            .statusCode(404)
    }

    @Test
    fun `unknown path returns not found`() {
        given()
            .`when`().get("/api/nonexistent")
            .then()
            .statusCode(404)
    }

    @Test
    fun `large request body is consumed through the listener`() {
        val requestBody = "x".repeat(256 * 1024)

        given()
            .contentType("text/plain")
            .body(requestBody)
            .`when`().post("/api/hello")
            .then()
            .statusCode(200)
            .body(equalTo("hello world"))
    }

    @Test
    fun `sequential requests do not accumulate duplicate handlers`() {
        repeat(10) {
            given()
                .`when`().get("/api/hello")
                .then()
                .statusCode(200)
                .body(equalTo("hello world"))
        }
    }
}

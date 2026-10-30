package dev.museframework.test.apikit

import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test

abstract class ApikitContract {
    @Test
    fun `GET routes to the APIKit operation flow`() {
        given()
            .`when`().get("/apikit/hello")
            .then()
            .statusCode(200)
            .body(containsString("hello via apikit"))
    }

    @Test
    fun `sequential APIKit requests reuse the configured router`() {
        repeat(5) {
            given()
                .`when`().get("/apikit/hello")
                .then()
                .statusCode(200)
                .body(containsString("hello via apikit"))
        }
    }

    @Test
    fun `POST routes JSON to the APIKit operation flow`() {
        given()
            .contentType("application/json")
            .body("""{"message":"test"}""")
            .`when`().post("/apikit/hello")
            .then()
            .statusCode(200)
            .body(containsString("hello via apikit"))
    }

    @Test
    fun `unknown APIKit path preserves the current error mapping`() {
        given()
            .`when`().get("/apikit/unknown")
            .then()
            .statusCode(500)
    }

    @Test
    fun `APIKit registration leaves the plain HTTP listener available`() {
        given()
            .`when`().get("/api/hello")
            .then()
            .statusCode(200)
            .body(containsString("hello world"))
    }
}

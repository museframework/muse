package dev.museframework.connectors.http.quarkus

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QuarkusRoutePatternTest {
    @Test
    fun `rejects empty and unterminated URI parameters`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuarkusRoutePattern.compile("/contract/{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuarkusRoutePattern.compile("/contract/{id")
        }
    }
}

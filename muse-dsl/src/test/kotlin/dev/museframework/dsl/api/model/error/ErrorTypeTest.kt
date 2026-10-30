package dev.museframework.dsl.api.model.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ErrorTypeTest {

    @Test
    fun `ASYNC_REJECTED should descend from MULE ANY`() {
        val rejected = ErrorType.Mule.ASYNC_REJECTED
        assertEquals("MULE", rejected.namespace)
        assertEquals("ASYNC_REJECTED", rejected.identifier)
        assertEquals(ErrorType.Mule.ANY, rejected.parent)
        assertTrue(rejected.isDescendantOf(ErrorType.Mule.ANY))
        assertEquals("MULE:ASYNC_REJECTED", rejected.toString())
    }

    @Test
    fun `ASYNC_INPUT_NOT_RETAINABLE should descend from MULE ANY`() {
        val notRetainable = ErrorType.Mule.ASYNC_INPUT_NOT_RETAINABLE
        assertEquals("MULE", notRetainable.namespace)
        assertEquals("ASYNC_INPUT_NOT_RETAINABLE", notRetainable.identifier)
        assertEquals(ErrorType.Mule.ANY, notRetainable.parent)
        assertTrue(notRetainable.isDescendantOf(ErrorType.Mule.ANY))
        assertEquals("MULE:ASYNC_INPUT_NOT_RETAINABLE", notRetainable.toString())
    }

    @Test
    fun `async rejection types are siblings rather than ancestors of each other`() {
        assertFalse(ErrorType.Mule.ASYNC_REJECTED.isDescendantOf(ErrorType.Mule.ASYNC_INPUT_NOT_RETAINABLE))
        assertFalse(ErrorType.Mule.ASYNC_INPUT_NOT_RETAINABLE.isDescendantOf(ErrorType.Mule.ASYNC_REJECTED))
    }
}

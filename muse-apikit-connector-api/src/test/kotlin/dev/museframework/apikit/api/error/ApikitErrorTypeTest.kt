package dev.museframework.apikit.api.error

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.api.model.error.isDescendantOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApikitErrorTypeTest {
    @Test
    fun `canonical APIKit errors are siblings under MULE ANY`() {
        assertEquals(ErrorType.Mule.ANY, ApikitErrorType.ANY.parent)

        val publicErrors =
            listOf(
                ApikitErrorType.BAD_REQUEST,
                ApikitErrorType.NOT_FOUND,
                ApikitErrorType.METHOD_NOT_ALLOWED,
                ApikitErrorType.NOT_ACCEPTABLE,
                ApikitErrorType.UNSUPPORTED_MEDIA_TYPE,
                ApikitErrorType.NOT_IMPLEMENTED,
            )

        publicErrors.forEach { error ->
            assertEquals(ApikitErrorType.ANY, error.parent)
            assertTrue(error.isDescendantOf(ErrorType.Mule.ANY))
        }
    }

    @Test
    fun `HTTP-specific failures do not match BAD_REQUEST handlers`() {
        val httpErrors =
            listOf(
                ApikitErrorType.NOT_FOUND,
                ApikitErrorType.METHOD_NOT_ALLOWED,
                ApikitErrorType.NOT_ACCEPTABLE,
                ApikitErrorType.UNSUPPORTED_MEDIA_TYPE,
                ApikitErrorType.NOT_IMPLEMENTED,
            )
        httpErrors.forEach { error ->
            assertTrue(error.isDescendantOf(ApikitErrorType.ANY))
            assertFalse(error.isDescendantOf(ApikitErrorType.BAD_REQUEST))
        }
    }

    @Test
    fun `legacy Muse validation identifiers retain their ancestry`() {
        assertTrue(ApikitErrorType.MISSING_HEADER.isDescendantOf(ApikitErrorType.BAD_REQUEST))
        assertTrue(ApikitErrorType.MISSING_QUERY_PARAM.isDescendantOf(ApikitErrorType.BAD_REQUEST))
        assertTrue(ApikitErrorType.INVALID_HEADER.isDescendantOf(ApikitErrorType.BAD_REQUEST))
        assertTrue(ApikitErrorType.INVALID_QUERY_PARAM.isDescendantOf(ApikitErrorType.BAD_REQUEST))
        assertTrue(ApikitErrorType.FLOW_NOT_FOUND.isDescendantOf(ApikitErrorType.BAD_REQUEST))
    }
}

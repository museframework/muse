package dev.museframework.apikit.api.error

import dev.museframework.dsl.api.model.error.isDescendantOf
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApikitErrorTypeTest {
    @Test
    fun `HTTP-specific failures are siblings of BAD_REQUEST`() {
        val httpErrors = listOf(
            ApikitErrorType.NOT_FOUND,
            ApikitErrorType.METHOD_NOT_ALLOWED,
            ApikitErrorType.UNSUPPORTED_MEDIA_TYPE,
        )
        httpErrors.forEach { error ->
            assertTrue(error.isDescendantOf(ApikitErrorType.ANY))
            assertFalse(error.isDescendantOf(ApikitErrorType.BAD_REQUEST))
        }
    }

    @Test
    fun `validation errors still match BAD_REQUEST handlers`() {
        assertTrue(ApikitErrorType.MISSING_HEADER.isDescendantOf(ApikitErrorType.BAD_REQUEST))
        assertTrue(ApikitErrorType.MISSING_QUERY_PARAM.isDescendantOf(ApikitErrorType.BAD_REQUEST))
    }
}

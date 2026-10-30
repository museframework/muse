package dev.museframework.file.api.model

import dev.museframework.dsl.api.model.error.isDescendantOf
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileModelsTest {
    @Test
    fun `file errors retain the connector hierarchy`() {
        assertTrue(FileErrorType.ACCESS_DENIED.isDescendantOf(FileErrorType.ANY))
        assertTrue(FileErrorType.FILE_DOESNT_EXIST.isDescendantOf(FileErrorType.CONNECTIVITY))
        assertTrue(FileErrorType.FILE_IS_NOT_DIRECTORY.isDescendantOf(FileErrorType.CONNECTIVITY))
        assertFalse(FileErrorType.FILE_IS_NOT_DIRECTORY.isDescendantOf(FileErrorType.ILLEGAL_PATH))
        assertTrue(FileErrorType.CONNECTIVITY.isDescendantOf(dev.museframework.dsl.api.model.error.ErrorType.Mule.CONNECTIVITY))
        assertEquals("FILE:FILE_ALREADY_EXISTS", FileErrorType.FILE_ALREADY_EXISTS.toString())
    }

    @Test
    fun `matcher rejects zero, negative, or contradictory size boundaries`() {
        assertFailsWith<IllegalArgumentException> { LocalFileMatcher(minSize = 0) }
        assertFailsWith<IllegalArgumentException> { LocalFileMatcher(maxSize = 0) }
        assertFailsWith<IllegalArgumentException> { LocalFileMatcher(minSize = -1) }
        assertFailsWith<IllegalArgumentException> { LocalFileMatcher(maxSize = -1) }
        assertFailsWith<IllegalArgumentException> { LocalFileMatcher(minSize = 10, maxSize = 9) }
        LocalFileMatcher(minSize = 1, maxSize = 1)
        assertFailsWith<IllegalArgumentException> {
            LocalFileMatcher(updatedInTheLast = Duration.ofSeconds(-1))
        }
    }

    @Test
    fun `subset uses Mule sorting defaults and validates bounds`() {
        val subset = LocalSubsetList()

        assertEquals(0, subset.limit)
        assertEquals(0, subset.offset)
        assertEquals(FileListSortCriteria.DATE_MODIFIED, subset.criteria)
        assertEquals(SortOrder.DESCENDING, subset.order)
        assertFailsWith<IllegalArgumentException> { LocalSubsetList(limit = -1) }
        assertFailsWith<IllegalArgumentException> { LocalSubsetList(offset = -1) }
    }
}

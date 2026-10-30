package dev.museframework.file.api.dsl

import dev.museframework.file.api.model.LocalFileMatcher
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FileMatcherDslTest {
    @Test
    fun `matcher DSL keeps the reusable name and the matcher value`() {
        val config =
            fileMatcher {
                name = "smallFileMatcher"
                matcher = LocalFileMatcher(filenamePattern = "*.txt", maxSize = 100)
            }

        assertEquals("smallFileMatcher", config.name)
        assertEquals("smallFileMatcher", config.docName)
        assertEquals("*.txt", config.matcher.filenamePattern)
        assertEquals(100L, config.matcher.maxSize)
    }
}

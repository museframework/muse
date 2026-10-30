package dev.museframework.connectors.sftp.client

import dev.museframework.sftp.api.model.SftpErrorType
import dev.museframework.sftp.api.model.SftpFileAttributes
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SftpClientUtilitiesTest {
    @Test
    fun `remote paths stay inside the configured working directory`() {
        val paths = SftpRemotePath("/exchange/./tenant")

        assertEquals("/exchange/tenant", paths.root)
        assertEquals("/exchange/tenant/inbound/file.txt", paths.resolve("/inbound/./file.txt"))
        assertEquals("/exchange/tenant/archive", paths.resolve("inbound/../archive"))
        assertEquals("/exchange/tenant/inbound/file.txt", paths.child("/exchange/tenant/inbound", "file.txt"))
        assertEquals("/exchange/tenant", paths.parent("/exchange/tenant"))
        assertEquals("file.txt", paths.name("/exchange/tenant/inbound/file.txt"))
        assertEquals("/inbound/file.txt", paths.visible("/exchange/tenant/inbound/file.txt"))

        val failure = assertFailsWith<SftpClientFailure> { paths.resolve("../outside") }
        assertEquals(SftpErrorType.ILLEGAL_PATH, failure.errorType)
        val invalidName = assertFailsWith<SftpClientFailure> { paths.child(paths.root, "nested/file.txt") }
        assertEquals(SftpErrorType.ILLEGAL_PATH, invalidName.errorType)
    }

    @Test
    fun `matcher combines glob type size and timestamp criteria`() {
        val matcher =
            SftpFileMatcher(
                filenamePattern = "orders-??.CSV",
                pathPattern = "**/inbound/*",
                fileType = SftpFileType.REGULAR_FILE,
                minSize = 10,
                maxSize = 100,
                modifiedSince = Instant.parse("2026-01-01T00:00:00Z"),
                modifiedUntil = Instant.parse("2026-12-31T23:59:59Z"),
                caseSensitive = false,
            )
        val matching = attributes("/tenant/inbound/orders-42.csv", size = 50)

        assertTrue(matcher.matches(matching))
        assertFalse(matcher.matches(matching.copy(size = 101)))
        assertFalse(matcher.matches(matching.copy(name = "orders-420.csv")))
        assertFalse(matcher.matches(matching.copy(directory = true, regularFile = false)))
        assertFalse(matcher.matches(matching.copy(modifiedAt = Instant.parse("2025-12-31T23:59:59Z"))))
    }

    @Test
    fun `matcher honors regex filename patterns from delivered Mule shapes`() {
        val matcher =
            SftpFileMatcher(
                filenamePattern = "regex:orders-\\d{4}\\.csv",
                fileType = SftpFileType.REGULAR_FILE,
            )

        assertTrue(matcher.matches(attributes("/inbox/orders-2026.csv", size = 1)))
        assertFalse(matcher.matches(attributes("/inbox/orders-26.csv", size = 1)))
        assertFalse(matcher.matches(attributes("/inbox/orders-2026.txt", size = 1)))
        assertFalse(matcher.matches(attributes("/inbox/regex:orders-2026.csv", size = 1)))
    }

    private fun attributes(
        path: String,
        size: Long,
    ) = SftpFileAttributes(
        path = path,
        name = path.substringAfterLast('/'),
        size = size,
        modifiedAt = Instant.parse("2026-06-01T12:00:00Z"),
        regularFile = true,
        directory = false,
        symbolicLink = false,
        permissions = 0b110100100,
    )
}

package dev.museframework.connectors.sftp.client

import dev.museframework.sftp.api.model.SftpFileAttributes
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType

internal fun SftpFileMatcher.matches(attributes: SftpFileAttributes): Boolean = compile().matches(attributes)

internal fun SftpFileMatcher.compile(): SftpFilePredicate =
    SftpFilePredicate(
        filenamePattern = filenamePattern?.let { patternRegex(it, caseSensitive) },
        pathPattern = pathPattern?.let { patternRegex(it, caseSensitive) },
        fileType = fileType,
        minSize = minSize,
        maxSize = maxSize,
        modifiedSince = modifiedSince,
        modifiedUntil = modifiedUntil,
    )

internal class SftpFilePredicate(
    private val filenamePattern: Regex?,
    private val pathPattern: Regex?,
    private val fileType: SftpFileType,
    private val minSize: Long?,
    private val maxSize: Long?,
    private val modifiedSince: java.time.Instant?,
    private val modifiedUntil: java.time.Instant?,
) {
    fun matches(attributes: SftpFileAttributes): Boolean {
        val modifiedAt = attributes.modifiedAt
        if (filenamePattern != null && !filenamePattern.matches(attributes.name)) return false
        if (pathPattern != null && !pathPattern.matches(attributes.path)) return false
        if (minSize != null && attributes.size < minSize) return false
        if (maxSize != null && attributes.size > maxSize) return false
        if (modifiedSince != null && (modifiedAt == null || modifiedAt.isBefore(modifiedSince))) return false
        if (modifiedUntil != null && (modifiedAt == null || modifiedAt.isAfter(modifiedUntil))) return false

        return when (fileType) {
            SftpFileType.ANY -> true
            SftpFileType.REGULAR_FILE -> attributes.regularFile
            SftpFileType.DIRECTORY -> attributes.directory
            SftpFileType.SYMBOLIC_LINK -> attributes.symbolicLink
        }
    }
}

private const val REGEX_PREFIX = "regex:"

private fun patternRegex(
    pattern: String,
    caseSensitive: Boolean,
): Regex {
    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    return if (pattern.startsWith(REGEX_PREFIX)) {
        Regex(pattern.substring(REGEX_PREFIX.length), options)
    } else {
        globRegex(pattern, options)
    }
}

private fun globRegex(
    pattern: String,
    options: Set<RegexOption>,
): Regex {
    val regex = StringBuilder("^")
    var index = 0
    while (index < pattern.length) {
        when (val character = pattern[index]) {
            '*' -> {
                if (index + 1 < pattern.length && pattern[index + 1] == '*') {
                    regex.append(".*")
                    index++
                } else {
                    regex.append("[^/]*")
                }
            }
            '?' -> regex.append("[^/]")
            '\\' -> regex.append('/')
            '/' -> regex.append('/')
            else -> regex.append(Regex.escape(character.toString()))
        }
        index++
    }
    regex.append('$')
    return Regex(regex.toString(), options)
}

package dev.museframework.connectors.sftp.client

import dev.museframework.sftp.api.model.SftpErrorType

internal class SftpRemotePath(
    workingDirectory: String,
) {
    private val rootSegments = normalizeSegments(workingDirectory, emptyList(), 0)
    val root: String = absolute(rootSegments)

    fun resolve(path: String): String =
        try {
            absolute(normalizeSegments(path, rootSegments, rootSegments.size))
        } catch (failure: IllegalArgumentException) {
            throw SftpClientFailure(
                SftpErrorType.ILLEGAL_PATH,
                "SFTP path '$path' escapes configured working directory '$root'",
                failure,
            )
        }

    fun child(
        resolvedDirectory: String,
        name: String,
    ): String {
        if (name.isBlank() || '/' in name || '\\' in name || name == "." || name == "..") {
            throw SftpClientFailure(
                SftpErrorType.ILLEGAL_PATH,
                "SFTP child name '$name' must be a single non-blank path segment",
            )
        }
        return absolute(
            normalizeSegments(
                name,
                segmentsOf(resolvedDirectory),
                rootSegments.size,
            ),
        )
    }

    fun parent(resolvedPath: String): String {
        val segments = segmentsOf(resolvedPath)
        return if (segments.size <= rootSegments.size) root else absolute(segments.dropLast(1))
    }

    fun name(resolvedPath: String): String = segmentsOf(resolvedPath).lastOrNull().orEmpty()

    fun visible(resolvedPath: String): String {
        val segments = segmentsOf(resolvedPath)
        require(segments.take(rootSegments.size) == rootSegments) {
            "resolved path '$resolvedPath' is outside working directory '$root'"
        }
        return absolute(segments.drop(rootSegments.size))
    }

    private fun normalizeSegments(
        path: String,
        initial: List<String>,
        minimumDepth: Int,
    ): List<String> {
        require('\u0000' !in path) { "NUL is not allowed in an SFTP path" }
        val result = initial.toMutableList()
        path.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    require(result.size > minimumDepth) { "path traversal outside root" }
                    result.removeAt(result.lastIndex)
                }
                else -> result += segment
            }
        }
        return result
    }

    private fun segmentsOf(path: String): List<String> =
        path.split('/').filter(String::isNotEmpty)

    private fun absolute(segments: List<String>): String =
        if (segments.isEmpty()) "/" else segments.joinToString(prefix = "/", separator = "/")
}
